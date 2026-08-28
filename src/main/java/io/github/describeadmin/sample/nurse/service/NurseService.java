package io.github.describeadmin.sample.nurse.service;

import io.github.describeadmin.mybatis.api.BaseService;
import io.github.describeadmin.sample.nurse.entity.NurseEntity;
import io.github.describeadmin.sample.nurse.mapper.NurseMapper;
import org.springframework.stereotype.Service;

/**
 * 护理员 Service。
 *
 * <p>由 codegen 生成。CRUD 与分页由 {@code BaseService} 提供，
 * 业务特有逻辑写在这里；生成器不会覆盖本文件中手工添加的方法
 * （重新生成时本文件默认被跳过，需覆盖请显式指定 --force）。
 */
@Service
public class NurseService extends BaseService<NurseMapper, NurseEntity> {
}
