# 家具模型参数 JSON 生成器

这是一个纯本地静态网页。填写家具模型的固定参数、颜色、材质、特征和人工风格评分后，可以复制或下载标准 JSON。

## 直接使用

双击 `index.html`，用 Chrome、Edge 或 Safari 打开即可。页面不上传数据，也不需要安装依赖。

如果浏览器限制了本地文件功能，可在 Pico 根目录运行：

```bash
python3 -m http.server 8000
```

然后访问：

```text
http://localhost:8000/tools/furniture-json-portal/
```

## 使用顺序

1. 填写模型 ID、名称和品类。
2. 填写云端 GLB 地址及来源许可证。
3. 填写米制尺寸、颜色、材质和可观察特征。
4. 人工设置北欧、侘寂、宜家、中式、工业风分数。
5. 中式可继续填写传统中式和新中式两个细分。
6. 右侧显示“可下载”后，点击“填写并下载 JSON”。

未勾选“已观察”或“已评估”的分数会输出为 `null`，避免把“不知道”误写成 `0`。所有已填写的风格分数都标记为 `human_input`。

## 文件

- `index.html`：页面结构。
- `styles.css`：页面样式。
- `core.js`：JSON 构造、校验和草稿清洗。
- `app.js`：交互、本地草稿、复制与下载。
- `../../config/furniture-model-parameter-definition.v1.json`：固定字段、特征和风格的机器可读定义。

浏览器草稿键为 `pico.furniture-json-portal.v1`，只保存在当前浏览器。清除浏览器数据会清除草稿，因此正式记录请下载 JSON。
