# 在线朗读规则说明

* 在线朗读规则为 url 规则，同书源 url
* js 参数
```
speakText //朗读文本
speakSpeed //朗读速度，5-50
```
* 手写规则的 access_token 有效期 30 天，过期需手动更换：
```
https://tsn.baidu.com/text2audio,{
    "method": "POST",
    "body": "tex={{java.encodeURI(speakText)}}&tok=粘贴你的access_token&cuid=legado&ctp=1&lan=zh&aue=3&per=0&spd=5&pit=5&vol=5"
}
```
* 旧地址 `http://tts.baidu.com/text2audio` 现在也要求 `tok`，不带会返回 err_no 501。
