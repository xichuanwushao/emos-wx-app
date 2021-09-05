package com.xichuan.emos.mapper;


import com.xichuan.emos.domain.TbUser;

import java.util.HashMap;
import java.util.Set;

public interface TB_UserMapperCust {
    public boolean haveRootUser();

    public int insert(HashMap param);

    public Integer searchIdByOpenId(String openId);

    public Set<String> searchUserPermissions(int userId);

    public TbUser searchById(int userId);

    public HashMap searchNameAndDept(int userId);

    public String searchUserHiredate(int userId);
}