# 封面测试图片

本目录图片由本项目生成，不含外部版权素材；执行 Java 测试不依赖 Python 或 Pillow。

使用 Pillow 12.3.0：cover.jpg/png/webp 来自 RGB 3×3、颜色 (46,90,140) 的 Image.new，按对应格式默认参数保存。large.png 来自 random.Random(42).randbytes(128*128*3) 的 RGB 128×128 Image.frombytes，保存为 PNG，用于超出 Servlet 缓冲区的真实 HTTP 断流测试。没有业务假数据或运行时默认封面。
