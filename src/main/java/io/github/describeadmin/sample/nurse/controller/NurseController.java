package io.github.describeadmin.sample.nurse.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.describeadmin.crypto.api.CryptoTemplate;
import io.github.describeadmin.mybatis.api.BaseController;
import io.github.describeadmin.sample.nurse.entity.NurseEntity;
import io.github.describeadmin.sample.nurse.mapper.NurseMapper;
import io.github.describeadmin.sample.nurse.service.NurseService;
import java.util.Map;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 护理员。
 *
 * <p>由 codegen 生成，之后手工补了敏感字段的盲索引搜索（framework-crypto-starter）。
 * 继承 {@code BaseController} 即获得 list / get / create / update / delete 五个标准端点。
 *
 * <p>列表查询支持的条件：
 * <ul>
 *   <li>{@code nurseName} —— 护理员姓名（右模糊）</li>
 *   <li>{@code employmentType} —— 从业类型（等值）</li>
 *   <li>{@code mobile} —— 手机号码（<b>密文列，走盲索引精确匹配</b>）</li>
 *   <li>{@code idCard} —— 身份证号码（<b>密文列，走盲索引精确匹配</b>）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/nurse")
public class NurseController extends BaseController<NurseService, NurseMapper, NurseEntity> {

    private final NurseService nurseService;
    private final CryptoTemplate cryptoTemplate;

    public NurseController(NurseService nurseService, CryptoTemplate cryptoTemplate) {
        this.nurseService = nurseService;
        this.cryptoTemplate = cryptoTemplate;
    }

    @Override
    protected NurseService getService() {
        return nurseService;
    }

    /**
     * 权限点前缀。
     *
     * <p>显式覆写，不依赖 {@code BaseController} 从 {@code @RequestMapping} 的推导：
     * apiPrefix 默认会把模块名里的下划线换成连字符（{@code my_module} →
     * {@code /api/my-module}），而 {@code menu-*.sql} 登记的权限点用的是模块名原样
     * （{@code my_module:list}）。靠推导会得到 {@code my-module:list}，
     * 与授权数据对不上，表现为<b>连 ADMIN 都被 403</b>——
     * 而错误信息里没有任何东西指向"权限点前缀拼错了"。
     */
    @Override
    public String permPrefix() {
        return "nurse";
    }

    /**
     * 列表查询的筛选条件。
     *
     * <p>空值不参与筛选，否则「不填任何条件」会退化成 {@code WHERE col = ''}，一条都查不出。
     * 姓名右模糊可走索引；从业类型等值。
     *
     * <p><b>手机号码 / 身份证号码是加密列</b>：直接拿明文去比 {@code mobile}/{@code id_card}
     * 密文列永远匹配不上。改为用 {@link CryptoTemplate#blindIndex(String)} 把明文算成 HMAC，
     * 等值命中拦截器自动填充的 {@code mobile_idx}/{@code id_card_idx} 盲索引列——
     * 因此这里只能做精确匹配，不支持手机号/身份证的模糊搜索（模糊搜索需要密文列全表解密，
     * 是设计上刻意不提供的）。
     */
    @Override
    protected Wrapper<NurseEntity> buildListWrapper(Map<String, String> params) {
        QueryWrapper<NurseEntity> wrapper = new QueryWrapper<>();
        wrapper.likeRight(text(params, "nurseName") != null, "nurse_name", text(params, "nurseName"));
        wrapper.eq(text(params, "employmentType") != null, "employment_type", text(params, "employmentType"));

        String mobile = text(params, "mobile");
        wrapper.eq(mobile != null, "mobile_idx", mobile == null ? null : cryptoTemplate.blindIndex(mobile));

        String idCard = text(params, "idCard");
        wrapper.eq(idCard != null, "id_card_idx", idCard == null ? null : cryptoTemplate.blindIndex(idCard));

        return wrapper;
    }

    /** 取参数，空串按未填处理 —— 前端清空输入框后通常传的是空串而不是不传。 */
    private static String text(Map<String, String> params, String key) {
        String value = params.get(key);
        return value == null || value.isBlank() ? null : value.trim();
    }
}
