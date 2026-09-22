# 长期项目笔记：网页聊天室（web-chatroom）

## 项目定位
按「比特就业课 - Java 网页版聊天程序」课件实现的网页版聊天室，目标是能独立手写全部功能（面试用）。课件 PDF 在 `C:\Users\a\Desktop\Java - 网页版聊天程序.pdf`。

## 技术栈（与课件有分歧，以本项目为准）
- Spring Boot 3.5.7 / Java 17（课件用 SpringBoot 2，用户选了 3）
- MyBatis-Plus 3.5.5（课件用原生 MyBatis + XML）
- JWT（jjwt 0.11.5）+ localStorage 鉴权（**课件用 HttpSession**）
- MD5 加盐（Md5Util：32 位 MD5 + 32 位盐 = 64 字符，**password 字段必须 varchar(64)**）
- 统一返回 DTO（request/response 包），错误统一由 GlobalExceptionHandler 返回 `{code, message}`

## 包结构约定
```
com.example.chatroom
├── controller     UserController ...
├── service / service.impl
├── mapper
├── pojo.dataobject   实体（User 字段为 userName，注意不是 username）
├── pojo.request / pojo.response   DTO
├── config         WebConfig（拦截器注册）
└── common
    ├── constant     Constant（请求头名、JWT claim 键名、request 域键名）
    ├── interceptor  LoginInterceptor（鉴权）、LogInterceptor（只打日志）
    ├── utils        JwtUtil、Md5Util、BeanTransfer
    └── exception    UserException、GlobalExceptionHandler
```

## 鉴权规范（已落地并验证）
- 请求头 `User-Token` 携带 JWT，头名/claim 键名/request 域键名**全部从 `Constant` 取**，禁止硬编码字符串。
- claim：`userId`（数字）、`username`。解析端用 `claims.get(...).toString()` 再转 Integer，别直接强转。
- `WebConfig` 采用「拦 `/**` + 排除放行清单」，新增接口默认需要登录。排除项必须含 `/error`，否则异常转发会被再拦一次、401 盖掉真实错误。
- 拦截器统一返回 `401 + {"code":401,"message":"..."}`。
- 前端 `client.js` 用 `$.ajaxSetup` 统一带 `User-Token` 头，用 `$(document).ajaxError` 统一处理 401 跳登录页。
- 静态页 `client.html` 放行，能否使用由前端 JS 调 `/userInfo` 判断。

## MyBatis-Plus 约定
- **实体主键必须标注 `@TableId`**（如 `@TableId(type = IdType.AUTO)`），否则 MP 不识别自增主键、insert 后不回填主键（注册接口曾返回 `userId:null`）。
- 列名**不要写死**，交给 MP 按 `map-underscore-to-camel-case: true` 推导。
- ⚠️ **真实库列名是蛇形**（`user_id`、`user_name`），而 `src/main/java/db.sql` 里写的是 `userId`/`username`，两者不一致，待统一后更新 db.sql。

## 关键约定
- **前端接口契约以 `static/js/client.js` 为准**，后端按它反推实现。
- 数据库：`java_chatroom`，用户名 root。
- 用户偏好：默认不落盘生成 .java 文件，代码与讲解直接在对话里给出；用户明确说"帮我改"时才动文件。

## 本地构建/运行（mvn 不在 PATH）
- Maven：`E:\IntelliJ IDEA 2026.2.2\plugins\maven-plugin\lib\maven3\bin\mvn.cmd`
- 编译：`mvn -o -B compile`；运行：加 `-Dspring-boot.run.arguments=--server.port=18080` 覆盖沙箱注入的随机端口。
- `spring-blog.log` 是应用日志（`logging.file.name`），用 Read 直接读会 GBK 乱码，用 Grep 更可靠。

## 待决策
- WebSocket 如何鉴权（JWT 无法通过浏览器原生 WebSocket 自定义请求头）。建议 token 挂 URL query + 自定义 HandshakeInterceptor。
- db.sql 的表/列命名统一为蛇形还是驼峰。
