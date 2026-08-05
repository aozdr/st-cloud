package com.stcloud.admin.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.stcloud.admin.dto.UpdateUserRequest;
import com.stcloud.admin.dto.CreateUserRequest;
import com.stcloud.admin.dto.UserManageVO;

public interface UserManageService {

    IPage<UserManageVO> listUsers(int page, int size);

    void updateUser(Long userId, UpdateUserRequest request);

    void deleteUser(Long userId);

    UserManageVO getUser(Long userId);

    UserManageVO createUser(CreateUserRequest request);
}
