// @name 百度在线语音
// @schema 1
// @version 1.0.0
// @uuid baidu_online_tts
// @author 阅读SK
// @url https://tsn.baidu.com/text2audio
// @enabled false
// @cookieJar false
// @audioType audio/mpeg
// @defaultSpeed 30
// @defaultVolume 50
// @defaultPitch 50
// @concurrentRate 200
// @maxConcurrency 2
// @description 百度智能云短文本在线合成。填写 API Key / Secret Key 后自动换取并缓存 access_token（30 天有效），无需手动维护。语速/音调/音量按 0-15 档映射。注意：单次文本上限 1024 GBK 字节，超长段落会合成失败。收费音色按百度控制台账单计费。

var BAIDU_TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token";
var BAIDU_TTS_URL = "https://tsn.baidu.com/text2audio";
var BAIDU_TOKEN_CACHE_SECONDS = 25 * 24 * 60 * 60;
var BAIDU_CUID_CACHE_SECONDS = 365 * 24 * 60 * 60;

// per, 名称, 性别, 场景, 音库
var BAIDU_VOICE_ROWS = [
    [0, "度小美-标准女主播", "female", "资讯", "基础音库"],
    [1, "度小宇-亲切男声", "male", "资讯", "基础音库"],
    [3, "度逍遥-情感男声", "male", "小说", "基础音库"],
    [4, "度丫丫-童声", "child", "小说", "基础音库"],
    [5, "度小娇-成熟女主播", "female", "资讯", "精品音库"],
    [103, "度米朵-可爱童声", "child", "小说", "精品音库"],
    [106, "度博文-专业男主播", "male", "资讯", "精品音库"],
    [110, "度小童-童声主播", "child", "小说", "精品音库"],
    [111, "度小萌-软萌妹子", "female", "小说", "精品音库"],
    [5003, "度逍遥-情感男声", "male", "小说", "精品音库"],
    [5118, "度小鹿-甜美女声", "female", "小说", "精品音库"],
    [4003, "度逍遥-情感男声", "male", "小说", "臻品音库"],
    [4007, "度小台-台湾女声", "female", "方言", "大模型音库"],
    [4100, "度小雯-活力女主播", "female", "资讯", "臻品音库"],
    [4103, "度米朵-可爱女声", "female", "小说", "臻品音库"],
    [4105, "度灵儿-清澈女声", "female", "小说", "臻品音库"],
    [4106, "度博文-专业男主播", "male", "资讯", "臻品音库"],
    [4114, "阿龙-说书男声", "male", "小说", "臻品音库"],
    [4115, "度小贤-电台男主播", "male", "小说", "臻品音库"],
    [4117, "度小乔-活泼女声", "female", "小说", "臻品音库"],
    [4119, "度小鹿-甜美女声", "female", "小说", "臻品音库"],
    [4129, "度小彦-知识男主播", "male", "资讯", "臻品音库"],
    [4132, "度阿闽-闽南男声", "male", "方言", "大模型音库"],
    [4134, "度阿锦-东北女声", "female", "方言", "大模型音库"],
    [4139, "度小蓉-四川女声", "female", "方言", "大模型音库"],
    [4140, "度小新-专业女主播", "female", "资讯", "臻品音库"],
    [4141, "度婉婉-甜美女声", "female", "小说", "臻品音库"],
    [4143, "度清风-配音男声", "male", "小说", "臻品音库"],
    [4144, "度姗姗-娱乐女声", "female", "娱乐", "臻品音库"],
    [4146, "度禧禧-阳光女声", "female", "资讯", "大模型音库"],
    [4147, "度云朵-可爱童声", "child", "小说", "臻品音库"],
    [4148, "度小夏-甜美女声", "female", "小说", "臻品音库"],
    [4149, "度星河-广告男声", "male", "资讯", "臻品音库"],
    [4150, "度湘玉-陕西女声", "female", "方言", "大模型音库"],
    [4154, "度老崔-北京男声", "male", "方言", "大模型音库"],
    [4156, "度言浩-年轻男声", "male", "小说", "大模型音库"],
    [4157, "度言静-明亮女声", "female", "小说", "大模型音库"],
    [4164, "度阿肯-主播男声", "male", "资讯", "臻品音库"],
    [4167, "度忠祥-纪录片男声", "male", "纪录片", "臻品音库"],
    [4172, "度筱林-天津女声", "female", "方言", "大模型音库"],
    [4176, "度有为-磁性男声", "male", "小说", "臻品音库"],
    [4179, "度泽言-温暖男声", "male", "小说", "大模型音库"],
    [4189, "度涵竹-开朗女声", "female", "小说", "大模型音库"],
    [4190, "度梓涵-甜美童声", "child", "小说", "臻品音库"],
    [4192, "度青川-温柔男声", "male", "小说", "臻品音库"],
    [4193, "度泽言-开朗男声", "male", "小说", "大模型音库"],
    [4194, "度嫣然-活泼女声", "female", "小说", "大模型音库"],
    [4195, "度怀安-磁性男声", "male", "小说", "大模型音库"],
    [4196, "度清影-甜美女声", "female", "小说", "大模型音库"],
    [4197, "度沁遥-知性女声", "female", "资讯", "大模型音库"],
    [4206, "度博文-综艺男声", "male", "娱乐", "臻品音库"],
    [4226, "南方-电台女主播", "female", "资讯", "臻品音库"],
    [4254, "度小清-广告女声", "female", "资讯", "臻品音库"],
    [4257, "四川小哥-四川男声", "male", "方言", "大模型音库"],
    [4259, "度小新-播音女声", "female", "资讯", "臻品音库"],
    [4277, "西贝-脱口秀女声", "female", "娱乐", "臻品音库"],
    [4278, "度小贝-知识女主播", "female", "资讯", "臻品音库"],
    [4288, "度晴岚-甜美女声", "female", "小说", "臻品音库"],
    [4340, "懒羊羊", "male", "娱乐", "臻品音库"],
    [4341, "猴哥", "male", "娱乐", "臻品音库"],
    [4342, "熊二", "male", "娱乐", "臻品音库"],
    [5147, "度常盈-客服女声", "female", "资讯", "臻品音库"],
    [5153, "度常悦-民生女主播", "female", "资讯", "臻品音库"],
    [5971, "度皮特-老外男声", "male", "娱乐", "臻品音库"],
    [5976, "度小皮-萌娃童声", "child", "小说", "臻品音库"],
    [5977, "台媒女声-台湾女声", "female", "方言", "大模型音库"],
    [5980, "度阿花-上海女声", "female", "方言", "大模型音库"],
    [6205, "度悠然-旁白男声", "male", "小说", "臻品音库"],
    [6221, "度云萱-旁白女声", "female", "小说", "臻品音库"],
    [6543, "度雨萌-邻家女孩", "female", "小说", "臻品音库"],
    [6546, "度清豪-逍遥侠客", "male", "小说", "臻品音库"],
    [6561, "度小乐-可爱童声", "child", "小说", "臻品音库"],
    [6562, "度雨楠-元气少女", "female", "小说", "臻品音库"],
    [6567, "度小柔-温柔女声", "female", "小说", "大模型音库"],
    [6602, "度清柔-温柔男神", "male", "小说", "臻品音库"],
    [6644, "度书宁-亲和女声", "female", "小说", "臻品音库"],
    [6746, "度书道-沉稳男声", "male", "小说", "臻品音库"],
    [6747, "度书古-情感男声", "male", "小说", "臻品音库"],
    [6748, "度书严-沉稳男声", "male", "小说", "臻品音库"],
    [20100, "度小粤-粤语女声", "female", "方言", "大模型音库"],
    [20101, "度晓芸-粤语女声", "female", "方言", "大模型音库"],
    [340701, "度舒然-营销女声", "female", "资讯", "臻品音库"],
    [340702, "度小飒-直播女声", "female", "娱乐", "臻品音库"],
    [340703, "度启明-直播男声", "male", "娱乐", "臻品音库"]
];

var BAIDU_VOICES = BAIDU_VOICE_ROWS.map(function (row) {
    return {
        id: String(row[0]),
        name: row[1],
        language: "zh-CN",
        gender: row[2],
        style: row[3],
        tags: ["百度", row[4]],
        extra: { per: String(row[0]), tier: row[4] }
    };
});

function options() {
    return [
        {
            key: "apiKey",
            label: "API Key",
            type: "password",
            defaultValue: ""
        },
        {
            key: "secretKey",
            label: "Secret Key",
            type: "password",
            defaultValue: ""
        }
    ];
}

function voices(options, ctx) {
    return BAIDU_VOICES;
}

function synthesize(text, voice, params, options, ctx) {
    var content = trimText(text);
    if (!content) throw "合成文本不能为空";
    var apiKey = trimText(options && options.apiKey);
    var secretKey = trimText(options && options.secretKey);
    if (!apiKey || !secretKey) {
        throw "请先在「脚本选项」中填写百度语音的 API Key 和 Secret Key";
    }
    var per = resolvePer(voice);
    var body = [
        "tex=" + java.encodeURI(content),
        "tok=" + java.encodeURI(accessToken(apiKey, secretKey)),
        "cuid=" + cuidValue(),
        "ctp=1",
        "lan=zh",
        "aue=3",
        "per=" + per,
        "spd=" + baiduLevel(params && params.speed, 15),
        "pit=" + baiduPitch(params && params.pitch, voice),
        "vol=" + baiduLevel(params && params.volume, 15)
    ].join("&");
    return {
        url: BAIDU_TTS_URL,
        method: "POST",
        requestContentType: "application/x-www-form-urlencoded",
        body: body,
        audioContentType: "audio/mpeg",
        timeout: 30,
        retry: 0
    };
}

function accessToken(apiKey, secretKey) {
    var cacheKey = "baidu_tts_token_" + apiKey + "_" + secretKey;
    var cached = cache.get(cacheKey);
    if (cached) {
        try {
            var parsed = JSON.parse(String(cached));
            if (parsed && parsed.access_token) {
                return String(parsed.access_token);
            }
        } catch (ignored) {
        }
    }
    var url = BAIDU_TOKEN_URL +
        "?grant_type=client_credentials" +
        "&client_id=" + java.encodeURI(apiKey) +
        "&client_secret=" + java.encodeURI(secretKey);
    var json = JSON.parse(String(java.ajax(url)));
    if (json.error) {
        throw "百度语音授权失败：" + (json.error_description || json.error);
    }
    if (!json.access_token) {
        throw "百度语音授权失败：返回中没有 access_token";
    }
    cache.put(
        cacheKey,
        JSON.stringify({ access_token: String(json.access_token) }),
        BAIDU_TOKEN_CACHE_SECONDS
    );
    return String(json.access_token);
}

function cuidValue() {
    var cached = cache.get("baidu_tts_cuid");
    if (cached) return String(cached);
    var value = "legadosk" + String(java.randomUUID()).replace(/-/g, "").substring(0, 24);
    cache.put("baidu_tts_cuid", value, BAIDU_CUID_CACHE_SECONDS);
    return value;
}

function resolvePer(voice) {
    if (voice && voice.extra && voice.extra.per) {
        return String(voice.extra.per);
    }
    var id = voice && voice.id ? String(voice.id) : "";
    return /^\d+$/.test(id) ? id : "0";
}

function baiduLevel(value, max) {
    var number = Number(value);
    if (!isFinite(number)) number = 50;
    var level = Math.round(number * max / 100);
    if (level < 0) return 0;
    if (level > max) return max;
    return level;
}

function baiduPitch(value, voice) {
    var tier = voice && voice.extra && voice.extra.tier;
    return baiduLevel(value, tier === "基础音库" ? 9 : 15);
}

function trimText(value) {
    return String(value == null ? "" : value)
        .replace(/[\r\n\t]+/g, " ")
        .replace(/\s{2,}/g, " ")
        .trim();
}
