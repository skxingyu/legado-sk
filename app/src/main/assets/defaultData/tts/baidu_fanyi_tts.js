// @name 百度翻译免密语音
// @schema 1
// @version 1.0.0
// @uuid baidu_fanyi_tts
// @author 阅读SK
// @url https://fanyi.baidu.com/gettts
// @enabled false
// @cookieJar false
// @audioType audio/mpeg
// @defaultSpeed 30
// @defaultVolume 50
// @defaultPitch 50
// @concurrentRate 200
// @maxConcurrency 2
// @description 百度翻译网页配音接口，无需 API Key。实测约 260 字以内稳定，更长文本可能返回空音频或 503，只适合短句试听；正式朗读请用「百度在线语音」。

var FANYI_TTS_URL = "https://fanyi.baidu.com/gettts";

function options() {
    return [];
}

function voices(options, ctx) {
    return [
        {
            id: "zh",
            name: "度小美-网页版",
            language: "zh-CN",
            gender: "female",
            style: "资讯",
            tags: ["百度", "免Key"]
        }
    ];
}

function synthesize(text, voice, params, options, ctx) {
    var content = trimText(text);
    if (!content) throw "合成文本不能为空";
    return {
        url: FANYI_TTS_URL +
            "?lan=zh&source=web&spd=" + baiduLevel(params && params.speed, 15) +
            "&text=" + java.encodeURI(content),
        method: "GET",
        audioContentType: "audio/mpeg",
        timeout: 20,
        retry: 0
    };
}

function baiduLevel(value, max) {
    var number = Number(value);
    if (!isFinite(number)) number = 50;
    var level = Math.round(number * max / 100);
    if (level < 0) return 0;
    if (level > max) return max;
    return level;
}

function trimText(value) {
    return String(value == null ? "" : value)
        .replace(/[\r\n\t]+/g, " ")
        .replace(/\s{2,}/g, " ")
        .trim();
}
