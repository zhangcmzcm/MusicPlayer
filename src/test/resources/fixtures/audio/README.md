# 自生成音频测试资源

silence.flac 为无外部版权素材的原生 FLAC 静音：8000Hz、单声道、16bit，共 256 个零采样，两帧各 128 个采样。STREAMINFO 含真实 PCM MD5，帧使用 constant 子帧、CRC-8 多项式 0x07 与 CRC-16 多项式 0x8005。测试运行仅读取此文件，不依赖编码器或 Python。

MP3 使用项目现有 AudioTestFixtures.minimalMp3 的 417 字节 MPEG1 Layer III 静音帧；长流测试重复其完整帧，不复制含标签的任意片段。
