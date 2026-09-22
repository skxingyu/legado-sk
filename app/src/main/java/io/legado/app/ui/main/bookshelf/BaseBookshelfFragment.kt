package io.legado.app.ui.main.bookshelf

import android.annotation.SuppressLint
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.core.view.indices
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.DialogBookshelfConfigBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.utils.applyUiMenuStyle
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.cache.CacheActivity
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.import.local.ImportBookActivity
import io.legado.app.ui.book.import.remote.RemoteBookActivity
import io.legado.app.ui.book.manage.BookshelfManageActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.main.MainViewModel
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.ui.widget.menu.SurfacePopupMenu
import io.legado.app.utils.checkByIndex
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCheckedIndex
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.readText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi

abstract class BaseBookshelfFragment(layoutId: Int) : VMBaseFragment<BookshelfViewModel>(layoutId),
    MainFragmentInterface {

    override val position: Int? get() = arguments?.getInt("position")

    val activityViewModel by activityViewModels<MainViewModel>()
    override val viewModel by viewModels<BookshelfViewModel>()

    /**
     * 「按备份清理本机书籍」选中的备份 zip。
     * 独立注册（不复用 [importBookshelf]）：后者只接受 txt/json 且语义是导书。
     */
    private val backupForCleanup = registerForActivityResult(HandleFileContract()) {
        val uri = it.uri ?: return@registerForActivityResult
        viewModel.cleanupByBackup(uri) { candidates, excludedCount ->
            showCleanupDialog(candidates, excludedCount)
        }
    }

    private val importBookshelf = registerForActivityResult(HandleFileContract()) {        kotlin.runCatching {
            it.uri?.readText(requireContext())?.let { text ->
                viewModel.importBookshelf(text, groupId)
            }
        }.onFailure {
            toastOnUi(it.localizedMessage ?: "ERROR")
        }
    }
    private val exportResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            alert(R.string.export_success) {
                if (uri.toString().isAbsUrl()) {
                    setMessage(DirectLinkUpload.getSummary())
                }
                val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                    editView.hint = getString(R.string.path)
                    editView.setText(uri.toString())
                }
                customView { alertBinding.root }
                okButton {
                    requireContext().sendToClip(uri.toString())
                }
            }
        }
    }
    abstract val groupId: Long
    abstract val books: List<Book>
    abstract var onlyUpdateRead: Boolean
    private var groupsLiveData: LiveData<List<BookGroup>>? = null
    private val waitDialog by lazy {
        WaitDialog(requireContext()).apply {
            setOnCancelListener {
                viewModel.addBookJob?.cancel()
            }
        }
    }

    /**
     * 合并重复书籍的进度框。
     * ⚠️ 刻意独立于 [waitDialog]：后者被「通过 URL 添加书籍」占用，
     * 共用一个实例会让两条流程互相覆盖文案、互相提前 dismiss。
     */
    private val mergeWaitDialog by lazy {
        WaitDialog(requireContext()).apply {
            setOnCancelListener {
                viewModel.mergeDuplicatesJob?.cancel()
            }
        }
    }

    abstract fun gotoTop()

    open fun back(): Boolean = false

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        menuInflater.inflate(R.menu.main_bookshelf, menu)
        if (AppConfig.moveSearchToBookshelf) {
            menu.add(0, R.id.menu_search, 0, R.string.search).apply {
                setIcon(R.drawable.ic_search)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
        }
    }

    protected open fun onSearchPlacementChanged() {
        supportToolbar?.menu?.let { menu ->
            menu.clear()
            onCompatCreateOptionsMenu(menu)
            menu.applyUiMenuStyle(requireContext())
        }
    }

    protected fun showBookshelfMenu(anchor: View) {
        SurfacePopupMenu(requireContext(), anchor).apply {
            inflate(R.menu.main_bookshelf)
            menu.applyUiMenuStyle(requireContext())
            setOnMenuItemClickListener {
                onCompatOptionsItemSelected(it)
                true
            }
            show()
        }
    }

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        super.onCompatOptionsItemSelected(item)
        when (item.itemId) {
            R.id.menu_remote -> startActivity<RemoteBookActivity>()
            R.id.menu_search -> startActivity<SearchActivity>()
            R.id.menu_update_toc -> activityViewModel.upToc(books.distinctBy { it.bookUrl }, onlyUpdateRead)
            R.id.menu_bookshelf_layout -> configBookshelf()
            R.id.menu_group_manage -> showDialogFragment<GroupManageDialog>()
            R.id.menu_add_local -> startActivity<ImportBookActivity>()
            R.id.menu_add_url -> showAddBookByUrlAlert()
            R.id.menu_bookshelf_manage -> startActivity<BookshelfManageActivity> {
                putExtra("groupId", groupId)
            }

            R.id.menu_merge_duplicates -> mergeDuplicates()
            R.id.menu_cleanup_by_backup -> cleanupByBackup()
            R.id.menu_download -> startActivity<CacheActivity> {
                putExtra("groupId", groupId)
            }

            R.id.menu_export_bookshelf -> viewModel.exportBookshelf(books) { file ->
                exportResult.launch {
                    mode = HandleFileContract.EXPORT
                    fileData =
                        HandleFileContract.FileData("bookshelf.json", file, "application/json")
                }
            }

            R.id.menu_import_bookshelf -> importBookshelfAlert(groupId)
            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
    }

    protected fun initBookGroupData() {
        groupsLiveData?.removeObservers(viewLifecycleOwner)
        groupsLiveData = appDb.bookGroupDao.show.apply {
            observe(viewLifecycleOwner) {
                upGroup(it)
            }
        }
    }

    abstract fun upGroup(data: List<BookGroup>)

    abstract fun upSort()

    override fun observeLiveBus() {
        observeEvent<Boolean>(PreferKey.moveSearchToBookshelf) {
            onSearchPlacementChanged()
        }
        viewModel.addBookProgressLiveData.observe(this) { count ->
            if (count < 0) {
                waitDialog.dismiss()
            } else {
                waitDialog.setText("添加中... ($count)")
            }
        }
        // 合并重复书籍：显隐只由 this（State）控制，收尾只信 onFinally ——
        // Coroutine 的注释明确「如果协程太快完成，回调会不执行」，
        // 因此不能用 onStart/onSuccess 做唯一的 show/dismiss 依据。
        viewModel.mergeDuplicatesState.observe(this) { running ->
            if (running) {
                mergeWaitDialog.setText(R.string.merge_duplicates_running)
                mergeWaitDialog.show()
            } else {
                mergeWaitDialog.dismiss()
            }
        }
        viewModel.mergeDuplicatesProgress.observe(this) { text ->
            mergeWaitDialog.setText(text)
        }
    }

    @SuppressLint("InflateParams")
    fun showAddBookByUrlAlert() {
        alert(titleResource = R.string.add_book_url) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "url"
            }
            customView { alertBinding.root }
            okButton {
                alertBinding.editView.text?.toString()?.let {
                    waitDialog.setText("添加中...")
                    waitDialog.show()
                    viewModel.addBookByUrl(it)
                }
            }
            cancelButton()
        }
    }

    /**
     * 「合并重复书籍」：把同书不同源的重复记录收敛成一条。
     *
     * 交互顺序刻意是「先扫描 → 再确认 → 后执行」：
     * 没有重复时直接提示返回，不弹确认框、不显示进度框。
     * 进度框用独立实例（[mergeWaitDialog]），不复用 [waitDialog] —— 后者已被
     * 「通过 URL 添加书籍」占用，复用会让两个流程互相覆盖文案与提前关闭。
     */
    private fun mergeDuplicates() {
        viewModel.mergeDuplicatesJob?.cancel()
        val job = viewModel.execute {
            viewModel.findDuplicateGroups()
        }
        job.onSuccess { groups ->
            if (groups.isEmpty()) {
                toastOnUi(R.string.merge_duplicates_none)
                return@onSuccess
            }
            val groupCount = groups.size
            val bookCount = groups.sumOf { group -> group.size }
            alert(
                title = getString(R.string.merge_duplicates_confirm_title),
                message = getString(
                    R.string.merge_duplicates_confirm_message,
                    groupCount,
                    bookCount
                )
            ) {
                okButton {
                    viewModel.mergeDuplicates(groups) { mergedGroups, mergedBooks ->
                        toastOnUi(
                            getString(
                                R.string.merge_duplicates_done,
                                mergedGroups,
                                mergedBooks
                            )
                        )
                    }
                }
                noButton()
            }
        }.onError {
            AppLog.put("扫描重复书籍出错\n${it.localizedMessage}", it)
            toastOnUi(it.localizedMessage ?: "ERROR")
        }
    }

    @SuppressLint("InflateParams")
    fun configBookshelf() {
        alert(titleResource = R.string.bookshelf_layout) {
            var bookshelfLayout = AppConfig.bookshelfLayout
            var bookshelfSort = AppConfig.bookshelfSort
            var showBookname = AppConfig.showBookname
            val alertBinding =
                DialogBookshelfConfigBinding.inflate(layoutInflater)
                    .apply {
                        if (AppConfig.bookGroupStyle !in 0..<spGroupStyle.count) {
                            AppConfig.bookGroupStyle = 0
                        }
                        if (bookshelfLayout !in rgLayout.indices) {
                            bookshelfLayout = 3
                            AppConfig.bookshelfLayout = 3
                        }
                        if (bookshelfSort !in rgSort.indices) {
                            bookshelfSort = 0
                            AppConfig.bookshelfSort = 0
                        }
                        if (showBookname !in rgbLayout.indices) {
                            showBookname = 0
                            AppConfig.showBookname = 0
                        }
                        spGroupStyle.setSelection(AppConfig.bookGroupStyle)
                        swShowUnread.isChecked = AppConfig.showUnread
                        swShowLastUpdateTime.isChecked = AppConfig.showLastUpdateTime
                        swShowWaitUpBooks.isChecked = AppConfig.showWaitUpCount
                        swShowBookshelfFastScroller.isChecked = AppConfig.showBookshelfFastScroller
                        rgLayout.checkByIndex(bookshelfLayout)
                        rgbLayout.checkByIndex(showBookname)
                        if (bookshelfLayout < 2) {
                            bookNameChoice.visibility = View.GONE
                        }
                        rgLayout.setOnCheckedChangeListener { group, checkedId ->
                            val index = group.getCheckedIndex()
                            bookNameChoice.visibility = if (index > 1) View.VISIBLE else View.GONE
                        }
                        rgSort.checkByIndex(bookshelfSort)
                        margin.progress = AppConfig.bookshelfMargin
                    }
            customView { alertBinding.root }
            okButton {
                alertBinding.apply {
                    var notifyMain = false
                    var recreate = false
                    if (AppConfig.bookGroupStyle != spGroupStyle.selectedItemPosition) {
                        AppConfig.bookGroupStyle = spGroupStyle.selectedItemPosition
                        notifyMain = true
                    }
                    if (showBookname != rgbLayout.getCheckedIndex()) {
                        AppConfig.showBookname = rgbLayout.getCheckedIndex()
                        recreate = true
                    }
                    if (AppConfig.bookshelfMargin != margin.progress) {
                        AppConfig.bookshelfMargin = margin.progress
                        recreate = true
                    }
                    if (AppConfig.showUnread != swShowUnread.isChecked) {
                        AppConfig.showUnread = swShowUnread.isChecked
                        postEvent(EventBus.BOOKSHELF_REFRESH, "")
                    }
                    if (AppConfig.showLastUpdateTime != swShowLastUpdateTime.isChecked) {
                        AppConfig.showLastUpdateTime = swShowLastUpdateTime.isChecked
                        postEvent(EventBus.BOOKSHELF_REFRESH, "")
                    }
                    if (AppConfig.showWaitUpCount != swShowWaitUpBooks.isChecked) {
                        AppConfig.showWaitUpCount = swShowWaitUpBooks.isChecked
                        activityViewModel.postUpBooksLiveData(true)
                    }
                    if (AppConfig.showBookshelfFastScroller != swShowBookshelfFastScroller.isChecked) {
                        AppConfig.showBookshelfFastScroller = swShowBookshelfFastScroller.isChecked
                        postEvent(EventBus.BOOKSHELF_REFRESH, "")
                    }
                    if (bookshelfSort != rgSort.getCheckedIndex()) {
                        AppConfig.bookshelfSort = rgSort.getCheckedIndex()
                        upSort()
                    }
                    if (bookshelfLayout != rgLayout.getCheckedIndex()) {
                        AppConfig.bookshelfLayout = rgLayout.getCheckedIndex()
                        if (AppConfig.bookshelfLayout < 2) {
                            activityViewModel.booksGridRecycledViewPool.clear()
                        } else {
                            activityViewModel.booksListRecycledViewPool.clear()
                        }
                        recreate = true
                    }
                    if (recreate) {
                        postEvent(EventBus.RECREATE, "")
                    } else if (notifyMain) {
                        postEvent(EventBus.NOTIFY_MAIN, false)
                    }
                }
            }
            cancelButton()
        }
    }


    private fun importBookshelfAlert(groupId: Long) {
        alert(titleResource = R.string.import_bookshelf) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "url/json"
            }
            customView { alertBinding.root }
            okButton {
                alertBinding.editView.text?.toString()?.let {
                    viewModel.importBookshelf(it, groupId)
                }
            }
            cancelButton()
            neutralButton(R.string.select_file) {
                importBookshelf.launch {
                    mode = HandleFileContract.FILE
                    allowExtensions = arrayOf("txt", "json")
                }
            }
        }
    }

    // ---- 按备份清理本机书籍（bug 2） -----------------------------------------

    /**
     * 入口：提示说明后让用户选一份备份 zip，扫描出「本机有、该备份没有」的书籍。
     *
     * 放在书架菜单而非恢复流程内是刻意的：删除不可逆，而 `RestoreJournal` 的快照
     * **从不登记 `legado.db`**（既有继承缺陷），把删除塞进恢复流程会与回滚机制纠缠不清。
     */
    private fun cleanupByBackup() {
        alert(title = getString(R.string.cleanup_by_backup)) {
            setMessage(R.string.cleanup_by_backup_message)
            okButton {
                backupForCleanup.launch {
                    mode = HandleFileContract.FILE
                    allowExtensions = arrayOf("zip")
                }
            }
            cancelButton()
        }
    }

    private fun showCleanupDialog(candidates: List<Book>, excludedCount: Int) {
        if (candidates.isEmpty()) {
            toastOnUi(R.string.cleanup_by_backup_none)
            return
        }
        // ⚠️ 默认全不勾：勾选=删除，默认勾选等于把不可逆操作变成一次误触即生效。
        val checked = BooleanArray(candidates.size) { false }
        val excludedNote = if (excludedCount > 0) {
            getString(R.string.cleanup_by_backup_excluded_note, excludedCount)
        } else {
            ""
        }
        alert(title = getString(R.string.cleanup_by_backup_confirm_title)) {
            setCustomView(createBookPickView(candidates, checked))
            setMessage(
                getString(
                    R.string.cleanup_by_backup_confirm_message,
                    candidates.size,
                    excludedNote
                )
            )
            okButton {
                val selected = candidates.filterIndexed { index, _ -> checked[index] }
                if (selected.isEmpty()) {
                    toastOnUi(R.string.cleanup_by_backup_none)
                    return@okButton
                }
                confirmCleanup(selected)
            }
            cancelButton()
        }
    }

    /** 二次确认：逐条列出将删书籍（不能只给数字，用户无法复核）。 */
    private fun confirmCleanup(selected: List<Book>) {
        val names = selected.joinToString("\n") { "· ${it.name}（${it.author}）" }
        alert(title = getString(R.string.cleanup_by_backup_final_title)) {
            setMessage(
                getString(
                    R.string.cleanup_by_backup_final_message,
                    selected.size,
                    names
                )
            )
            okButton {
                viewModel.deleteBooksByCleanup(selected) { deleted, manifestPath ->
                    toastOnUi(getString(R.string.cleanup_by_backup_done, deleted))
                    manifestPath?.let {
                        AppLog.put(getString(R.string.cleanup_by_backup_list_saved, it))
                    }
                }
            }
            cancelButton()
        }
    }

    /**
     * 书籍多选列表。行点击热区**只限复选框本身**，不整行可点 ——
     * 整行可点会让一次误触即改变「将删除」的集合（该模式在恢复项选择处已存在，此处收紧）。
     */
    private fun createBookPickView(books: List<Book>, checked: BooleanArray): View {
        val context = requireContext()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 6.dpToPx(), 0, 6.dpToPx())
        }
        books.forEachIndexed { index, book ->
            val checkBox = AppCompatCheckBox(context).apply {
                isChecked = checked[index]
                setOnCheckedChangeListener { _, isChecked -> checked[index] = isChecked }
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                minimumHeight = 48.dpToPx()
                setPadding(20.dpToPx(), 8.dpToPx(), 20.dpToPx(), 8.dpToPx())
                addView(checkBox, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        text = book.name
                        textSize = 16f
                        includeFontPadding = false
                    })
                    addView(TextView(context).apply {
                        text = "${book.author} · ${book.originName}"
                        textSize = 12f
                        alpha = 0.68f
                        setPadding(0, 5.dpToPx(), 0, 0)
                    })
                }, LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    marginStart = 8.dpToPx()
                })
            }
            container.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        return ScrollView(context).apply {
            addView(container, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.55f).toInt()
            )
        }
    }

}
