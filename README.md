# describeadmin sample-app

**这个仓库存在的意义是：给你一个正确的起点，而不是给你一个 demo。**

在 `describeadmin-archetype` 交付之前，业务方接入后端的推荐方式就是
从这里复制。它刻意**不继承** `framework-parent`，以真实业务方的姿态消费框架。

完整步骤见 [快速开始](https://github.com/describeadmin/docs/blob/main/QUICKSTART.md)。

## 跑起来

```bash
# 1. 数据库
docker run -d --name da-mysql -p 3307:3306 \
  -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=describeadmin \
  -e MYSQL_USER=app -e MYSQL_PASSWORD=app \
  mysql:5.7 --character-set-server=utf8mb4 --collation-server=utf8mb4_general_ci

# 2. 后端（监听 8090）
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

默认账号 `admin` / `admin123`。

> ⚠️ `local` profile 每次启动都会重放建表与种子脚本，**只能用于本地开发**。

## 这份 POM 替你挡掉的三个坑

这三条不是风格问题，是**已实测的失败**。它们的共同特征是报错信息与真实原因
相距很远，靠读文档发现不了——这也正是「接入不能靠文档，必须靠模板」的由来。

| POM 里的哪一行 | 不写会怎样 |
|---|---|
| `<mysql.version>8.2.0</mysql.version>` | 父 POM 继承的 `dependencyManagement` 优先级高于 import 的 BOM，实际解析到新版驱动，**连 MySQL 5.7 直接失败** |
| `maven-toolchains-plugin` | 用 `PATH` 上恰好存在的 JDK 编译，报「**不支持发行版本 17**」 |
| `@MapperScan("你自己的包.**.mapper")` | 照常写即可，见下文说明 |

### 关于 `@MapperScan`

早期版本里，业务方写 `@MapperScan("自己的包")` 会导致框架的系统管理 Mapper
全部扫不到、登录直接失败（`MapperScan` 一旦存在，MyBatis 的自动扫描就不再生效）。

**这个问题框架侧已经解决**：`FrameworkSystemAutoConfiguration` 上显式声明了
`@MapperScan("io.github.describeadmin.system.mapper")`，自行登记扫描路径。
因此你写自己的 `@MapperScan` 是安全的，**而且是必要的**——不写的话
扫不到的是你自己的 Mapper。

`application-local.yml` 里还有一条同类的：

```yaml
spring.sql.init.encoding: UTF-8
```

不写的话 Spring 用**平台默认编码**读 SQL 脚本，中文 Windows 上是 GBK，
库里中文全是乱码——而 `COUNT(*)` 校验完全正常，环境看起来非常健康。

## 改造成你自己的工程

1. 改 `pom.xml` 的 `groupId` / `artifactId` / `<name>`
2. 包名 `io.github.describeadmin.sample` → 你自己的
3. **删掉 `project` 示例模块**：`src/main/java/.../project/`、
   `src/main/resources/db/schema-biz_project.sql`、`menu-biz_project.sql`，
   以及 `application-local.yml` 里对这两个 SQL 的引用
4. 新建 `application-dev.yml` / `application-prod.yml` 指向你的真实数据库

第 3 步跳过的话，你的菜单里会一直挂着一个「项目管理」。

## 关于 `project` 这个模块

它**完全由 codegen 生成**，一行手写代码都没有——这是刻意的：
它同时是「生成器产物能否在真实框架下编译运行」的持续验证。
`codegen-specs/project.yaml` 就是它的全部输入。

## 许可证

[Apache-2.0](./LICENSE)
