package com.dovideo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dovideo.entity.UserInfo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserInfoMapper extends BaseMapper<UserInfo> {
}
