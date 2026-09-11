package io.legado.app.plugin

import android.content.Context

/**
 * 自有构建（app flavor）插件引导。
 *
 * 当前只注册内置 AI 出厂值：OpenCode Zen 免费通道要求请求携带会话与客户端标识头
 * （缺失则返回 400 MissingSessionID "OpenCode's free tier can only be used in OpenCode"），
 * 故在此登记默认 LLM 供应商的出厂请求头。会话 id 按设备派生，见
 * [AiBuiltinDefaults.openCodeSessionId]。
 *
 * 本 flavor 不注册百度 TTS 等专有引擎，其代码不参与编译与打包。
 */
object AppPlugins {

    fun init(context: Context) {
        AiBuiltinDefaults.register(object : AiBuiltinDefaults.Plugin {
            override fun builtinSiliconFlowApiKey(): String = ""

            override fun builtinZhipuApiKey(): String = ""

            override fun defaultLlmHeaders(): String =
                AiBuiltinDefaults.openCodeHeaders(AiBuiltinDefaults.openCodeSessionId())
        })
    }
}
