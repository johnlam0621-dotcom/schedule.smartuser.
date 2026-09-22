package com.smartuser.schedule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartuser.schedule.model.InspectionRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 巡检记录单表 Mapper。
 *
 * 功能作用：
 * 1. 对应 schedule_inspection 表，提供导入记录、Routes map 记录的单表增删改查能力。
 * 2. 当前查询使用 MyBatis-Plus BaseMapper 和 Wrapper 构造，符合“单表使用 MyBatis-Plus”的项目约定。
 * 3. 后续如增加跨表路线统计或用户关联查询，应新增 XML 映射文件承载多表 SQL。
 */
public interface InspectionMapper extends BaseMapper<InspectionRecord> {
  @Select("<script>SELECT COUNT(*) FROM inspection_photo WHERE inspection_id IN " +
      "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
  long countAttachedMedia(@Param("ids") List<Long> ids);
  /**
   * 导入时使用单条多值 SQL，避免远程 MySQL 逐行往返导致一个 Week 等待数分钟。
   */
  int insertBatch(@Param("records") List<InspectionRecord> records);
}
