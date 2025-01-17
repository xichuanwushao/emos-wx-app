package com.xichuan.emos.service;

import cn.hutool.core.date.DateField;
import cn.hutool.core.date.DateRange;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import com.xichuan.emos.config.SystemConstants;
import com.xichuan.emos.domain.TbCheckin;
import com.xichuan.emos.domain.TbFaceModel;
import com.xichuan.emos.exception.BusinessException;
import com.xichuan.emos.mail.EmailTask;
import com.xichuan.emos.mapper.*;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Scope;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;

@Service
@Scope("prototype")
@Slf4j
public class CheckinServiceImpl implements CheckinService{

    @Autowired
    private SystemConstants constants;

    @Resource
    private TbHolidaysMapperCust holidaysMapperCust;

    @Resource
    private TbWorkdayMapperCust workdayMapperCust;

    @Resource
    private TbCheckinMapperCust checkinMapperCust;

    @Resource
    private TbFaceModelMapperCust faceModelMapperCust;

    @Resource
    private TbCityMapperCust cityMapperCust;

   @Resource
    private TB_UserMapperCust userMapperCust;

    @Value("${emos.face.createFaceModelUrl}")
    private String createFaceModelUrl;

    @Value("${emos.face.checkinUrl}")
    private String checkinUrl;

    @Value("${emos.code}")
    private String code;

    @Value("${emos.email.hr}")
    private String hrEmail;



    @Autowired
    private EmailTask emailTask;


    @Override
    public String validCanCheckIn(int userId, String date) {
        boolean bool_1 = holidaysMapperCust.searchTodayIsHolidays() != null ? true : false;
        boolean bool_2 = workdayMapperCust.searchTodayIsWorkday() != null ? true : false;
        String type = "工作日";
        if (DateUtil.date().isWeekend()) {
            type = "节假日";
        }
        if (bool_1) {
            type = "节假日";
        } else if (bool_2) {
            type = "工作日";
        }

        if (type.equals("节假日")) {
            return "节假日不需要考勤";
        } else {
            DateTime now = DateUtil.date();
            String start = DateUtil.today() + " " + constants.attendanceStartTime;
            String end = DateUtil.today() + " " + constants.attendanceEndTime;
            DateTime attendanceStart = DateUtil.parse(start);
            DateTime attendanceEnd = DateUtil.parse(end);
            if(now.isBefore(attendanceStart)){
                return "没到上班考勤开始时间";
            }
            else if(now.isAfter(attendanceEnd)){
                return "超过了上班考勤结束时间";
            }else {
                HashMap map=new HashMap();
                map.put("userId",userId);
                map.put("date",date);
                map.put("start",start);
                map.put("end",end);
                boolean bool=checkinMapperCust.haveCheckin(map)!=null?true:false;
                return bool?"今日已经考勤，不用重复考勤" : "可以考勤";
            }
        }
    }

    @Override
    public void checkin(HashMap param) {
        Date d1=DateUtil.date();
        Date d2=DateUtil.parse(DateUtil.today()+" "+constants.attendanceTime);
        Date d3=DateUtil.parse(DateUtil.today()+" "+constants.attendanceEndTime);
        int status=1;
        if(d1.compareTo(d2)<=0){
            status=1;
        }
        else if(d1.compareTo(d2)>0&&d1.compareTo(d3)<0){
            status=2;
        }
        else{
            throw new BusinessException("超出考勤时间段，无法考勤");
        }
        int userId= (Integer) param.get("userId");
        String faceModel=faceModelMapperCust.searchFaceModel(userId);
        if(faceModel==null){
            throw new BusinessException("不存在人脸模型");
        }
        else{
            String path=(String)param.get("path");
            HttpRequest request= HttpUtil.createPost(checkinUrl);
            request.form("photo", FileUtil.file(path),"targetModel",faceModel);
            request.form("code",code);
            HttpResponse response=request.execute();
            if(response.getStatus()!=200){
                log.error("人脸识别服务异常");
                throw new BusinessException("人脸识别服务异常");
            }
            String body=response.body();
            if("无法识别出人脸".equals(body)||"照片中存在多张人脸".equals(body)){
                throw new BusinessException(body);
            }
            else if("False".equals(body)){
                throw new BusinessException("签到无效，非本人签到");
            }
            else if("True".equals(body)){
            //TODO 查询疫情风险等级
            //TODO 保存签到记录
            //查询疫情风险等级
            int risk=1;
            String city= (String) param.get("city");
            String district= (String) param.get("district");
            String address= (String) param.get("address");
            String country= (String) param.get("country");
            String province= (String) param.get("province");
            if(!StrUtil.isBlank(city)&&!StrUtil.isBlank(district)){
                String code=cityMapperCust.searchCode(city);
                try{
                    if(code==null){code="xa";
                        log.info("未获取到城市code "+city+" 将默认西安市");
                    }
                    String url = "http://m." + code + ".bendibao.com/news/yqdengji/?qu=" + district;
                    Document document= Jsoup.connect(url).get();
                    Elements elements=document.getElementsByClass("list-content");
                    if(elements.size()>0){
                        Element element=elements.get(0);
                        String result=element.select("p:last-child").text();
//                        result="高风险";
                        if("高风险".equals(result)){
                            risk=3;
                            //TODO 发送告警邮件
                            HashMap<String,String> map=userMapperCust.searchNameAndDept(userId);
                            String name = map.get("name");
                            String deptName = map.get("dept_name");
                            deptName = deptName != null ? deptName : "";
                            SimpleMailMessage message=new SimpleMailMessage();
                            message.setTo(hrEmail);
                            message.setSubject("员工" + name + "身处高风险疫情地区警告");
                            message.setText(deptName + "员工" + name + "，" + DateUtil.format(new Date(), "yyyy年MM月dd日") + "处于" + address + "，属于新冠疫情高风险地区，请及时与该员工联系，核实情况！");

                            emailTask.sendAsync(message);
                        }
                        else if("中风险".equals(result)){
                            risk=2;
                        }
                    }
                }catch (Exception e){
                    log.error("执行异常",e);
                    throw new BusinessException("获取风险等级失败");
                }
            }
            //保存签到记录
            TbCheckin entity=new TbCheckin();
            entity.setUserId(userId);
            entity.setAddress(address);
            entity.setCountry(country);
            entity.setProvince(province);
            entity.setCity(city);
            entity.setDistrict(district);
            entity.setStatus((byte) status);
            entity.setRisk(risk);
            entity.setDate(DateUtil.today());
            entity.setCreateTime(d1);
            checkinMapperCust.insert(entity);
            }
        }
    }
    @Override
    public void createFaceModel(int userId, String path) {
        HttpRequest request=HttpUtil.createPost(createFaceModelUrl);
        request.form("photo",FileUtil.file(path));
        request.form("code",code);
        HttpResponse response=request.execute();
        String body=response.body();
        if("无法识别出人脸".equals(body)||"照片中存在多张人脸".equals(body)){
            throw new BusinessException(body);
        }
        else{
            TbFaceModel entity=new TbFaceModel();
            entity.setUserId(userId);
            entity.setFaceModel(body);
            faceModelMapperCust.insert(entity);
        }
    }

    @Override
    public HashMap searchTodayCheckin(int userId) {
        HashMap map=checkinMapperCust.searchTodayCheckin(userId);
        return map;
    }

    @Override
    public long searchCheckinDays(int userId) {
        long days=checkinMapperCust.searchCheckinDays(userId);
        return days;
    }

    @Override
    public ArrayList<HashMap> searchWeekCheckin(HashMap param) {
        ArrayList<HashMap> checkinList=checkinMapperCust.searchWeekCheckin(param);
        ArrayList holidaysList=holidaysMapperCust.searchHolidaysInRange(param);
        ArrayList workdayList=workdayMapperCust.searchWorkdayInRange(param);
        DateTime startDate=DateUtil.parseDate(param.get("startDate").toString());//获取本周的开始日期
        DateTime endDate=DateUtil.parseDate(param.get("endDate").toString());//获取本周的结束日期
        DateRange range=DateUtil.range(startDate,endDate, DateField.DAY_OF_MONTH);//获取本周每一天的日期对象
        ArrayList<HashMap> list=new ArrayList<>();//查看本周每一天是工作日 还是休息日 工作日需要去判断当天的考勤情况
        range.forEach(one->{
            String date=one.toString("yyyy-MM-dd");
            String type="工作日";
            if(one.isWeekend()){//先粗浅判断今天是不是周末 来判断是否节假日
                type="节假日";
            }
            if(holidaysList!=null&&holidaysList.contains(date)){//查看节假日是否包含这一天
                type="节假日";
            }
            else if(workdayList!=null&&workdayList.contains(date)){//如果这一天在工作日中存在 那么这一天就是工作日
                type="工作日";
            }
            String status="";//如果周五这一天还没有到 那么周五不能算作是旷工 所以初始值是空字符串
            if(type.equals("工作日")&&DateUtil.compare(one,DateUtil.date())<=0){//1.如果当天是工作日去查看考勤结果  2.还要判定这一天是不是已经发生了
                //one代表本周的某一天 date是当前的天 如果小于0代表已经发生了
                status="缺勤";
                boolean flag=false;
                for (HashMap<String,String> map:checkinList){//取出checkin一天记录保存map
                    if(map.containsValue(date)){//map是否含有当前日期
                        status=map.get("status");//如果含有当前日期 取出map里面的考勤结果
                        flag=true;
                        break;
                    }
                }
                DateTime endTime=DateUtil.parse(DateUtil.today()+" "+constants.attendanceEndTime);//如果当天的考勤没有结束 不能判定为考勤
                String today=DateUtil.today();//当前日期的字符串
                if(date.equals(today)&&DateUtil.date().isBefore(endTime)&&flag==false){
                    status="";
                }
            }
            HashMap map=new HashMap();
            map.put("date",date);
            map.put("status",status);
            map.put("type",type);
            map.put("day",one.dayOfWeekEnum().toChinese("周"));
            list.add(map);
        });
        return list;
    }

    @Override
    public ArrayList<HashMap> searchMonthCheckin(HashMap param) {
        return this.searchWeekCheckin(param);
    }

}

