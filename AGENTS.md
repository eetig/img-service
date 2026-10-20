# AGENTS.md — file-service（原 img-service）

> 项目级约定写在 `../hnd_factory/AGENTS.md`（或用浏览器看
> <https://github.com/eetig/hnd_factory/blob/main/AGENTS.md>）。**先读它**。

## 这个仓库特有的

- **开工先 `git pull --ff-only`，收工必须 `git push`**。
- 一个服务、两个域：`/api/img/**` 图片（会**重编码成 PNG**）、`/api/file/**` 文档（**字节原样**）。
- 对象名由服务生成：图片 `<32hex>.png`；文档 `archive/<32hex>.<白名单扩展名>`。
  **读/删只认自己生成的形状** —— 桶是业务共享桶，别碰别的业务的对象。
- `spring.application.name = file-service`；后端有两个 Feign 客户端指向它
  （`ImgFeignClient` 默认 contextId、`FileFeignClient` 用 `fileServiceArchive`）——
  **同服务名多客户端必须不同 contextId**，否则后端启动即崩。
- `spring.servlet.multipart.max-file-size` 与代码里的上限**必须成对改**（30MB）。
- 门禁：`gradlew test`（当前 23 用例）。