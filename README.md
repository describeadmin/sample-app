# describeadmin sample-app

**这个仓库是框架的兼容性门禁与活样本，不是你的起点。**

> ⚠️ **要新建工程，用脚手架，别从这里复制**：
>
> ```bash
> mvn archetype:generate -B \
>   -DarchetypeGroupId=io.github.describeadmin \
>   -DarchetypeArtifactId=describeadmin-archetype \
>   -DarchetypeVersion=0.1.1 \
>   -DgroupId=com.acme -DartifactId=my-server -Dpackage=com.acme.myserver
> ```
>
> 生成的是没有业务模块、可直接登录的工程。从本仓库复制的话，
> 你还要回头删掉 `project` 示例模块和它的两个 SQL——那正是脚手架要消灭的步骤。

本仓库的用途是：以真实业务方的姿态消费框架（刻意**不继承** `framework-parent`），
每次框架发版前跑一遍完整业务流程，验证"业务方视角"是否真的成立。
`project` 模块则持续验证 codegen 的产物能否在真实框架下编译运行。

它仍然值得你读——下面那两个坑和 `project` 模块的形态，是照着写业务代码的最好参考。

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

## 这份 POM 里值得注意的几行

它们不是风格问题，是**已实测的失败**。共同特征是报错信息与真实原因相距很远，
靠读文档发现不了——这也正是「接入不能靠文档，必须靠模板」的由来。
前两条脚手架已经替你写好了。

| POM 里的哪一行 | 不写会怎样 |
|---|---|
| `<mysql.version>8.2.0</mysql.version>` | 父 POM 继承的 `dependencyManagement` 优先级高于 import 的 BOM，实际解析到新版驱动，**连 MySQL 5.7 直接失败** |
| `@MapperScan("你自己的包.**.mapper")` | 照常写即可，见下文说明 |
| `maven-toolchains-plugin` | **这一条业务工程不要抄**，见下 |

### 为什么本仓库有 toolchains 而脚手架没有

本仓库刻意用**最低支持版本 JDK 17** 构建，端到端证明"业务方只有 Java 17 也能用框架"，
toolchains 是为了让这件事与开发机上的 `PATH` 无关——这是**框架团队的验证需要**。

业务工程不该抄：那里唯一的要求是 Maven 自身跑在 JDK 17+
（`spring-boot-maven-plugin:repackage` 的硬要求），满足了 `release=17` 就必然能编。
而业务方开发机上大多没有 `~/.m2/toolchains.xml`，抄过去只会以
`Cannot find matching toolchain` 直接打死构建。

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

## 如果你还是要从这里复制

首选仍然是本文开头的脚手架命令。确实要复制的话，四步不能少：

1. 改 `pom.xml` 的 `groupId` / `artifactId` / `<name>`
2. 包名 `io.github.describeadmin.sample` → 你自己的
3. **删掉 `project` 示例模块**：`src/main/java/.../project/`、
   `src/main/resources/db/schema-biz_project.sql`、`menu-biz_project.sql`，
   以及 `application-local.yml` 里对这两个 SQL 的引用
4. 删掉 `pom.xml` 里的 `maven-toolchains-plugin`（理由见上一节），
   并新建 `application-dev.yml` / `application-prod.yml` 指向你的真实数据库

第 3 步跳过的话，你的菜单里会一直挂着一个「项目管理」。

## 关于 `project` 这个模块

它**完全由 codegen 生成**，一行手写代码都没有——这是刻意的：
它同时是「生成器产物能否在真实框架下编译运行」的持续验证。
`codegen-specs/project.yaml` 就是它的全部输入。

## 许可证

[Apache-2.0](./LICENSE)
