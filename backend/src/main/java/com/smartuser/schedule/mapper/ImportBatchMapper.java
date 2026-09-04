package com.smartuser.schedule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartuser.schedule.model.ImportBatch;

/**
 * 导入批次单表 Mapper。
 *
 * 功能作用：
 * 1. 对应 schedule_import_batch 表，用于记录每次上传文件的文件名、类型、总行数、成功/失败行数和导入人。
 * 2. InspectionService 在导入开始时创建批次，在导入/同步完成后回写统计信息。
 * 3. 当前仅做单表操作，直接继承 MyBatis-Plus BaseMapper。
 */
public interface ImportBatchMapper extends BaseMapper<ImportBatch> {
}
