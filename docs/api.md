# 阅读[API](/app/src/main/java/io/legado/app/api/controller)

## 对于[Web](/app/src/main/java/io/legado/app/web/)的配置

您需要先在设置中启用"Web 服务"。

## 使用

### Web

以下说明假设您的操作在本机进行，且开放端口为**默认的 1122**（可在「我的 → 其它设置 → Web 端口」中修改；端口不在 `1024..65530` 内时回退为 1122）。  
WebSocket 端口固定为 **HTTP 端口 + 1**（即默认 1123），由 `WebService` 在同一处启动。  
如果您要从远程计算机访问[阅读]()，请将`127.0.0.1`替换成手机IP。

#### 插入单个书源

请求BODY内容为`JSON`字符串，  
格式参考[这个文件](/app/src/main/java/io/legado/app/data/entities/BookSource.kt)

```
URL = http://127.0.0.1:1122/saveBookSource
URL = http://127.0.0.1:1122/saveRssSource
Method = POST
```

#### 插入多个书源or订阅源

请求BODY内容为`JSON`字符串，  
格式参考[这个文件](/app/src/main/java/io/legado/app/data/entities/BookSource.kt)，**为数组格式**。

```
URL = http://127.0.0.1:1122/saveBookSources
URL = http://127.0.0.1:1122/saveRssSources
Method = POST
```

#### 获取书源

```
URL = http://127.0.0.1:1122/getBookSource?url=xxx
URL = http://127.0.0.1:1122/getRssSource?url=xxx
Method = GET
``` 

#### 获取所有书源or订阅源

```
URL = http://127.0.0.1:1122/getBookSources
URL = http://127.0.0.1:1122/getRssSources
Method = GET
```

#### 删除多个书源or订阅源

请求BODY内容为`JSON`字符串，  
格式参考[这个文件](/app/src/main/java/io/legado/app/data/entities/BookSource.kt)，**为数组格式**。

```
URL = http://127.0.0.1:1122/deleteBookSources
URL = http://127.0.0.1:1122/deleteRssSources
Method = POST
```

#### 调试源

key为书源搜索关键词，tag为源链接

```
URL = ws://127.0.0.1:1123/bookSourceDebug
URL = ws://127.0.0.1:1123/rssSourceDebug
Message = { key: [String], tag: [String] }
```

#### 书源发现 / 探索

书源「发现」与「探索」能力。`*DiscoverSources*` 两条等价（同一处理函数）；`*ExploreBooks*` 与 `*DiscoverBooks*` 两条等价。

```
URL = http://127.0.0.1:1122/getBookSourceDiscoverSources
URL = http://127.0.0.1:1122/getBookSourceDiscover
Method = GET
```

```
URL = http://127.0.0.1:1122/getBookSourceExploreKinds?url=xxx
Method = GET
```

```
URL = http://127.0.0.1:1122/getBookSourceExploreBooks?url=xxx&exploreUrl=xxx&page=1
URL = http://127.0.0.1:1122/getBookSourceDiscoverBooks?url=xxx&exploreUrl=xxx&page=1
Method = GET
```

`url` 为书源地址；`exploreUrl` 与 `page` 可选——`exploreUrl` 缺省时回退书源自身的 `exploreUrl`（二者皆空则返回「发现地址不能为空」），`page` 缺省为 1。

#### 书源导入与登录信息

`/importBookSource` 与 `/saveBookSource` 等价，`/importBookSources` 与 `/saveBookSources` 等价（均转调同一处理函数）：

```
URL = http://127.0.0.1:1122/importBookSource
URL = http://127.0.0.1:1122/importBookSources
Method = POST
Body = 书源 JSON（单条 / 数组）
```

登录数据：`/loginBookSource` 与 `/saveBookSourceLogin` 等价（均保存登录数据并触发登录），
请求 Body 需含 `type`（`bookSource`/`rssSource`，缺省 `bookSource`）与 `url`（源地址），可带 `loginInfo`：

```
URL = http://127.0.0.1:1122/loginBookSource
URL = http://127.0.0.1:1122/saveBookSourceLogin
Method = POST
Body = { "type": "bookSource", "url": "xxx", "loginInfo": {...} }
```

读取登录信息用 `/getBookSourceLoginInfo` 或 `/getBookSourceLogin`（等价），参数为 `url`（亦可写 `key`）：

```
URL = http://127.0.0.1:1122/getBookSourceLoginInfo?url=xxx
URL = http://127.0.0.1:1122/getBookSourceLogin?url=xxx
Method = GET
```

#### 阅读排版配置（Web 端）

```
URL = http://127.0.0.1:1122/getReadConfig
Method = GET
```

```
URL = http://127.0.0.1:1122/saveReadConfig
Method = POST
Body = 阅读排版配置 JSON
```

#### 添加本地书籍

以 multipart 表单上传本地书籍文件，需带两个字段：

- `fileName`（表单参数，必填）——保存的文件名；缺失返回「fileName 不能为空」。
- `fileData`（文件字段，必填）——书籍文件内容；缺失返回「fileData 不能为空」。

```
URL = http://127.0.0.1:1122/addLocalBook
Method = POST
Form = fileName=xxx.txt, fileData=<文件>
```

#### 刷新目录

```
URL = http://127.0.0.1:1122/refreshToc?url=xxx
Method = GET
```

#### 获取替换规则

```
URL = http://127.0.0.1:1122/getReplaceRules
Method = GET
```

#### 替换规则管理

请求BODY内容为`JSON`字符串，  
替换规则参考[这个文件](/app/src/main/java/io/legado/app/data/entities/ReplaceRule.kt)。

⚠️ 以下三条接口接收的是**单个 `ReplaceRule` 对象**（`GSON.fromJsonObject<ReplaceRule>`），
**不是数组**；传数组会返回「格式不对」。

##### 删除

```
URL = http://127.0.0.1:1122/deleteReplaceRule
Method = POST
Body = ReplaceRule（单个对象）
```
##### 插入

```
URL = http://127.0.0.1:1122/saveReplaceRule
Method = POST
Body = ReplaceRule（单个对象）
```

##### 测试

返回测试文本 text 的替换结果。⚠️ `text` 是**字符串**（源码硬转型 `as String`），传数组会抛 `ClassCastException`。

```
URL = http://127.0.0.1:1122/testReplaceRule
Method = POST
Body = { "rule": ReplaceRule, "text": "待替换文本" }
```

#### 搜索在线书籍

若想获取对应的书籍的目录正文 请先**插入书籍**以启用缓存，如果试读后决定不添加到书籍，请**删除书籍**

```
URL = ws://127.0.0.1:1123/searchBook
Message = { key: [String] }
```

#### 插入书籍

请求BODY内容为`JSON`字符串，  
格式参考[这个文件](/app/src/main/java/io/legado/app/data/entities/Book.kt)。

```
URL = http://127.0.0.1:1122/saveBook
Method = POST
```

#### 删除书籍

```
URL = http://127.0.0.1:1122/deleteBook
Method = POST
```

#### 获取所有书籍

```
URL = http://127.0.0.1:1122/getBookshelf
Method = GET
```

获取APP内的所有书籍。

#### 获取书籍章节列表

```
URL = http://127.0.0.1:1122/getChapterList?url=xxx
Method = GET
```

获取指定图书的章节列表。

#### 获取书籍内容

```
URL = http://127.0.0.1:1122/getBookContent?url=xxx&index=1
Method = GET
```

获取指定图书的第`index`章节的文本内容。

#### 获取封面

```
URL = http://127.0.0.1:1122/cover?path=xxxxx
Method = GET
```

#### 获取正文图片

```
URL = http://127.0.0.1:1122/image?url=${bookUrl}&path=${picUrl}&width=${width}
Method = GET
```

#### 保存书籍进度

请求BODY内容为`JSON`字符串，  
格式参考[这个文件](/app/src/main/java/io/legado/app/data/entities/BookProgress.kt)。

```
URL = http://127.0.0.1:1122/saveBookProgress
Method = POST
```

### [Content Provider](/app/src/main/java/io/legado/app/api/ReaderProvider.kt)


* `providerHost`为`包名.readerProvider`（清单中声明为 `${applicationId}.readerProvider`），如正式版 `io.legado.app.c.readerProvider`；不同包地址不同，防止冲突安装失败
* ⚠️ 该 Provider 在清单中为 `android:exported="true"` 且**未声明任何权限保护**（全库无 `io.legado.READ_WRITE` 权限定义），任何应用均可访问。**不要**在集成时按「需声明权限」处理。
* 以下出现的`providerHost`请自行替换

#### 插入单个书源or订阅源

创建`Key="json"`的`ContentValues`，内容为`JSON`字符串，  
格式参考[这个文件](/app/src/main/java/io/legado/app/data/entities/BookSource.kt)

```
URL = content://providerHost/bookSource/insert
URL = content://providerHost/rssSource/insert
Method = insert
```

> ⚠️ **已知实现缺陷（上游继承，未修）**：`ReaderProvider.sMatcher` 把**每个 `rssSource*` URI 都映射到了对应的
> bookSource RequestCode**（`rssSource/insert`→`SaveBookSource`、`rssSources/insert`→`SaveBookSources`、
> `rssSources/delete`→`DeleteBookSources`、`rssSource/query`→`GetBookSource`、`rssSources/query`→`GetBookSources`；
> 见 `ReaderProvider.kt:38-42`）。因此经 Provider 的「订阅源」读/写**实际都作用在书源表上**；
> 代码中真正的 `SaveRssSource`/`SaveRssSources`/`GetRssSource`/`GetRssSources` 分支
> （`ReaderProvider.kt:90,94,131,132`）**因无 URI 映射而全部不可达**。
> 订阅源相关能力请改用 HTTP 接口（`/saveRssSources`、`/getRssSources`）或先验证实际行为。

#### 插入多个书源or订阅源

创建`Key="json"`的`ContentValues`，内容为`JSON`字符串，  
格式参考[这个文件](/app/src/main/java/io/legado/app/data/entities/BookSource.kt)，**为数组格式**。

```
URL = content://providerHost/bookSources/insert
URL = content://providerHost/rssSources/insert
Method = insert
```

#### 获取书源or订阅源

获取指定URL对应的书源信息。⚠️ 但如上方警示所述，`rssSource/query` 实际被映射到书源查询，
故当前两条 URI 都会按**书源**表查询。  
用`Cursor.getString(0)`取出返回结果。

```
URL = content://providerHost/bookSource/query?url=xxx
URL = content://providerHost/rssSource/query?url=xxx
Method = query
```

#### 获取所有书源or订阅源

获取APP内的所有书源或订阅源。⚠️ 但如上方警示所述，`rssSources/query` 实际被映射到书源查询，
故当前两条 URI 都会返回**书源**列表。  
用`Cursor.getString(0)`取出返回结果。

```
URL = content://providerHost/bookSources/query
URL = content://providerHost/rssSources/query
Method = query
```

#### 删除多个书源or订阅源

⚠️ 与 insert 不同：删除把 **JSON 数组字符串放在 `selection` 参数**中（`ContentValues` 不参与），
格式参考[这个文件](/app/src/main/java/io/legado/app/data/entities/BookSource.kt)，**为数组格式**。

```
URL = content://providerHost/bookSources/delete
URL = content://providerHost/rssSources/delete
Method = delete
```

#### 插入书籍

创建`Key="json"`的`ContentValues`，内容为`JSON`字符串，  
格式参考[这个文件](/app/src/main/java/io/legado/app/data/entities/Book.kt)。

```
URL = content://providerHost/book/insert
Method = insert
```

#### 获取所有书籍

获取APP内的所有书籍。  
用`Cursor.getString(0)`取出返回结果。

```
URL = content://providerHost/books/query
Method = query
```

#### 获取书籍章节列表

获取指定图书的章节列表。   
用`Cursor.getString(0)`取出返回结果。

```
URL = content://providerHost/book/chapter/query?url=xxx
Method = query
```

#### 获取书籍内容

获取指定图书的第`index`章节的文本内容。     
用`Cursor.getString(0)`取出返回结果。

```
URL = content://providerHost/book/content/query?url=xxx&index=1
Method = query
```

#### 获取封面

```
URL = content://providerHost/book/cover/query?path=xxxx
Method = query
```

#### 刷新目录（Provider）

在数据库中重新抓取并写入指定书籍的章节列表。

```
URL = content://providerHost/book/refreshToc/query?url=xxx
Method = query
```

#### 保存书籍进度（Provider 不支持）

⚠️ **Content Provider 未暴露此能力**：`ReaderProvider` 的 `RequestCode` 枚举中虽有 `SaveBookProgress`，
但**没有为它注册任何 URI**（`sMatcher` 中无对应 `addURI`），因此经 Provider 无法调用。
保存进度请改用上文的 HTTP 接口 `POST /saveBookProgress`。

