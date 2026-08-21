package io.github.describeadmin.sample;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.describeadmin.system.entity.SysConfig;
import io.github.describeadmin.system.entity.SysDictData;
import io.github.describeadmin.system.entity.SysDictType;
import io.github.describeadmin.system.mapper.SysConfigMapper;
import io.github.describeadmin.system.mapper.SysDictDataMapper;
import io.github.describeadmin.system.service.SysConfigService;
import io.github.describeadmin.system.service.SysDictDataService;
import io.github.describeadmin.system.service.SysDictTypeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 字典与参数配置的读穿缓存/失效验证（框架提供）。
 *
 * <p>关键手法：绕过 {@code Service}（直接用 {@code Mapper}）改库，模拟"缓存还没失效"的
 * 中间状态——如果 {@link SysDictDataService#listByType}/{@link SysConfigService#getValue}
 * 真的读了缓存，这时候应该看不到绕过 Service 的改动；等走 {@code Service} 的写方法，
 * 缓存才应该失效。只断言"命中了预期的具体值"，不只看数量（CLAUDE.md 3.6）。
 */
@DisplayName("字典与参数配置（框架提供）")
class DictConfigModuleIT extends AbstractMySqlIntegrationTest {

    @Autowired SysDictTypeService dictTypeService;
    @Autowired SysDictDataService dictDataService;
    @Autowired SysDictDataMapper dictDataMapper;
    @Autowired SysConfigService configService;
    @Autowired SysConfigMapper configMapper;

    @Test
    @DisplayName("字典类型与数据的基本 CRUD 可用")
    void dictCrud() {
        SysDictType type = new SysDictType();
        type.setDictType("dict-crud-test");
        type.setDictName("CRUD测试字典");
        type.setStatus(1);
        dictTypeService.save(type);
        assertThat(dictTypeService.list()).extracting(SysDictType::getDictType).contains("dict-crud-test");

        dictDataService.save(newDictData("dict-crud-test", "选项一", "1"));
        assertThat(dictDataService.listByType("dict-crud-test"))
                .extracting(SysDictData::getDictLabel)
                .containsExactly("选项一");
    }

    @Test
    @DisplayName("listByType 读穿缓存：命中期间看不到绕过 Service 插入的新记录")
    void dictDataCacheHit() {
        String dictType = "dict-cache-test";
        dictDataService.save(newDictData(dictType, "旧选项", "old"));
        assertThat(dictDataService.listByType(dictType))
                .extracting(SysDictData::getDictLabel).containsExactly("旧选项");

        dictDataMapper.insert(newDictData(dictType, "缓存之外插入的选项", "bypass"));

        assertThat(dictDataService.listByType(dictType))
                .as("命中缓存，不应该看到绕过 Service 插入的新记录")
                .extracting(SysDictData::getDictLabel)
                .containsExactly("旧选项");
    }

    @Test
    @DisplayName("字典数据写操作后缓存失效，下一次查询能看到新数据")
    void dictDataCacheEvictedAfterWrite() {
        String dictType = "dict-evict-test";
        dictDataService.save(newDictData(dictType, "选项A", "a"));
        assertThat(dictDataService.listByType(dictType)).hasSize(1);

        dictDataService.save(newDictData(dictType, "选项B", "b"));

        assertThat(dictDataService.listByType(dictType))
                .as("写操作应让缓存失效，而不是继续返回写之前的旧结果")
                .extracting(SysDictData::getDictLabel)
                .containsExactlyInAnyOrder("选项A", "选项B");
    }

    @Test
    @DisplayName("参数配置读穿缓存、支持默认值，写操作后缓存失效")
    void configGetValueCaching() {
        assertThat(configService.getValue("config-not-exists", "默认值")).isEqualTo("默认值");

        SysConfig config = new SysConfig();
        config.setConfigKey("config-cache-test");
        config.setConfigValue("v1");
        config.setConfigName("缓存测试参数");
        configService.save(config);
        assertThat(configService.getValue("config-cache-test")).isEqualTo("v1");

        // 绕过 Service 直接改库
        SysConfig bypass = configMapper.selectOne(
                new QueryWrapper<SysConfig>().eq("config_key", "config-cache-test"));
        bypass.setConfigValue("v2-bypass");
        configMapper.updateById(bypass);
        assertThat(configService.getValue("config-cache-test"))
                .as("命中缓存，不应该看到绕过 Service 的改动")
                .isEqualTo("v1");

        // 走 Service 更新，缓存应该失效
        SysConfig update = new SysConfig();
        update.setId(bypass.getId());
        update.setConfigValue("v3");
        configService.updateById(update);
        assertThat(configService.getValue("config-cache-test")).isEqualTo("v3");
    }

    private static SysDictData newDictData(String dictType, String label, String value) {
        SysDictData data = new SysDictData();
        data.setDictType(dictType);
        data.setDictLabel(label);
        data.setDictValue(value);
        data.setSort(0);
        data.setStatus(1);
        return data;
    }
}
