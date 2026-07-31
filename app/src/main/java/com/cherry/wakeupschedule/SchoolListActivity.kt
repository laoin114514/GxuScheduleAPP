package com.cherry.wakeupschedule

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cherry.wakeupschedule.adapter.SchoolAdapter
import com.cherry.wakeupschedule.ui.component.StyledDialog
import com.cherry.wakeupschedule.ui.theme.ThemeManager

class SchoolListActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: SchoolAdapter

    private val schools = listOf(
        // 河南省一本高校（重点）
        School("郑州大学", "https://jw.v.zzu.edu.cn/eams/login.action"),
        School("河南大学", "https://xk.henu.edu.cn/"),
        School("河南师范大学", "https://jwc.htu.edu.cn"),
        School("河南农业大学", "http://jw.henau.edu.cn/cas/login.action"),
        School("河南科技大学", "https://jwsxy.haust.edu.cn"),
        School("河南理工大学", "https://zhjw.hpu.edu.cn/eams/login.action"),
        School("河南工业大学", "https://jwglxt.haut.edu.cn/jwglxt/xtgl/login_slogin.html"),
        School("河南财经政法大学", "https://xk.huel.edu.cn/"),
        School("华北水利水电大学", "https://jwmis.ncwu.edu.cn"),
        School("郑州轻工业大学", "https://portal.zzuli.edu.cn"),
        School("河南中医药大学", "https://jwxt.hactcm.edu.cn/zy21w/defaults.htm"),
        School("河南医药大学", "https://qgjw.xxmu.edu.cn/cas/login.action"),
        School("信阳师范大学", "http://jwgl.xynu.edu.cn/jwglxt/xtgl/login"),
        School("洛阳师范学院", "http://211.67.81.80/jwweb/"),
        School("南阳师范学院", "http://nysyjw.nynu.edu.cn/nysfjw/cas/login.action"),
        School("安阳师范学院", "https://jwglxt.aynu.edu.cn/"),
        School("许昌学院", "https://jwglxt.xcu.edu.cn/jwglxt/xtgl/login_slogin.html"),
        School("河南科技学院", "http://jwgl.hist.edu.cn"),
        School("郑州航空工业管理学院", "http://jwglxt.zua.edu.cn/"),
        School("中原工学院", "https://xsxk.zut.edu.cn"),
        
        // 985高校
        School("清华大学", "https://academic.tsinghua.edu.cn"),
        School("北京大学", "https://elective.pku.edu.cn"),
        School("中国人民大学", "https://jw.ruc.edu.cn/Njw2017/login.html"),
        School("北京航空航天大学", "http://jiaowu.buaa.edu.cn"),
        School("北京理工大学", "http://jwms.bit.edu.cn"),
        School("中国农业大学", "https://newjw.cau.edu.cn/jsxsd/"),
        School("北京师范大学", "https://ss.bnu.edu.cn"),
        School("中央民族大学", "http://jwjs.muc.edu.cn"),
        School("复旦大学", "https://jwfw.fudan.edu.cn"),
        School("上海交通大学", "https://i.sjtu.edu.cn"),
        School("同济大学", "http://1.tongji.edu.cn"),
        School("华东师范大学", "http://byyt.ecnu.edu.cn"),
        School("南开大学", "https://eamis.nankai.edu.cn"),
        School("天津大学", "http://classes.tju.edu.cn"),
        School("重庆大学", "https://my.cqu.edu.cn/enroll"),
        School("浙江大学", "http://zdbk.zju.edu.cn"),
        School("南京大学", "https://xk.nju.edu.cn"),
        School("东南大学", "https://newxk.urp.seu.edu.cn"),
        School("大连理工大学", "http://jxgl.dlut.edu.cn/student/ucas-sso/login"),
        School("东北大学", "https://jwxt.neu.edu.cn"),
        School("中南大学", "http://csujwc.its.csu.edu.cn"),
        School("湖南大学", "http://hdjw.hnu.edu.cn"),
        School("四川大学", "http://zhjw.scu.edu.cn"),
        School("电子科技大学", "https://eportal.uestc.edu.cn"),
        School("中山大学", "https://jwxt.sysu.edu.cn"),
        School("华南理工大学", "http://xsjw2018.jw.scut.edu.cn"),
        School("武汉大学", "https://jwgl.whu.edu.cn"),
        School("华中科技大学", "http://wsxk.hust.edu.cn"),
        School("山东大学", "https://jwxt.wh.sdu.edu.cn/jsxsd/caslogin.jsp"),
        School("中国海洋大学", "http://jwgl2024.ouc.edu.cn/jsxsd"),
        School("吉林大学", "https://icourses.jlu.edu.cn"),
        School("哈尔滨工业大学", "http://jwts.hit.edu.cn"),
        School("厦门大学", "https://xk.xmu.edu.cn"),
        School("中国科学技术大学", "https://jw.ustc.edu.cn"),
        School("西安交通大学", "https://jwxt.xjtu.edu.cn"),
        School("西北工业大学", "https://jwxt.nwpu.edu.cn"),
        School("西北农林科技大学", "https://newehall.nwafu.edu.cn"),
        School("兰州大学", "http://jwk.lzu.edu.cn/academic/login/lzu/loginIds6Valid.jsp"),
        
        // 非985的211高校
        School("北京交通大学", "https://jwc.bjtu.edu.cn/"),
        School("北京科技大学", "https://seam.ustb.edu.cn/jwgl/login.html"),
        School("北京化工大学", "https://jwglxt.buct.edu.cn"),
        School("北京邮电大学", "https://jwxt.bupt.edu.cn"),
        School("北京林业大学", "http://newjwxt.bjfu.edu.cn/"),
        School("北京中医药大学", "http://jw.bucm.edu.cn"),
        School("北京外国语大学", "https://jwxt.bfsu.edu.cn/jwglxt"),
        School("中国传媒大学", "https://jwc.cuc.edu.cn"),
        School("中央财经大学", "http://xuanke.cufe.edu.cn/jwglxt"),
        School("对外经济贸易大学", "http://jwc.uibe.edu.cn/"),
        School("北京体育大学", "https://i.bsu.edu.cn"),
        School("中国政法大学", "http://jwxt.cupl.edu.cn/"),
        School("华北电力大学", "https://jwxt.ncepu.edu.cn/"),
        School("中国矿业大学(北京)", "https://jwxt.cumtb.edu.cn/"),
        School("中国石油大学(北京)", "http://bk.cup.edu.cn/student/login"),
        School("中国地质大学(北京)", "https://jwc.cugb.edu.cn/"),
        School("上海财经大学", "https://login.sufe.edu.cn/login"),
        School("上海外国语大学", "https://jw.shisu.edu.cn/home"),
        School("华东理工大学", "https://jwc.ecust.edu.cn/"),
        School("东华大学", "http://jw.dhu.edu.cn/"),
        School("上海大学", "https://jwxt.shu.edu.cn"),
        School("南京航空航天大学", "https://aao-eas.nuaa.edu.cn/eams/login.action"),
        School("南京理工大学", "https://jwc.njust.edu.cn/"),
        School("河海大学", "http://jwxs.hhu.edu.cn"),
        School("南京农业大学", "https://jwxt.njau.edu.cn"),
        School("中国药科大学", "http://jwxt.cpu.edu.cn"),
        School("南京师范大学", "http://jwc.njnu.edu.cn/"),
        School("苏州大学", "http://xk.suda.edu.cn/"),
        School("江南大学", "https://jwc.jiangnan.edu.cn/"),
        School("中国矿业大学", "http://jwxt.cumt.edu.cn/jwglxt"),
        School("武汉理工大学", "http://sso.jwc.whut.edu.cn/"),
        School("中南财经政法大学", "http://jwxt.zuel.edu.cn/jsxsd/sso.jsp"),
        School("华中师范大学", "https://xk.ccnu.edu.cn/jwglxt/xtgl/login_slogin.html"),
        School("华中农业大学", "http://jw.hzau.edu.cn"),
        School("中国地质大学(武汉)", "https://jwc.cug.edu.cn/"),
        School("长安大学", "http://xk.chd.edu.cn/"),
        School("陕西师范大学", "http://jwgl.snnu.edu.cn"),
        School("西安电子科技大学", "https://jwc.xidian.edu.cn/jwglxt.htm"),
        School("西南交通大学", "http://jwc.swjtu.edu.cn/service/login.html"),
        School("西南财经大学", "http://jwxt.swufe.edu.cn/xtgl/login_slogin.html"),
        School("四川农业大学", "https://jiaowu.sicau.edu.cn"),
        School("辽宁大学", "http://jwstudent.lnu.edu.cn/"),
        School("大连海事大学", "https://my.dlmu.edu.cn/"),
        School("湖南师范大学", "http://jwglnew.hunnu.edu.cn"),
        School("暨南大学", "https://jw.jnu.edu.cn"),
        School("华南师范大学", "https://jwxt.scnu.edu.cn/"),
        School("中国石油大学(华东)", "http://jwxt.upc.edu.cn"),
        School("东北师范大学", "http://dsjx.nenu.edu.cn"),
        School("东北林业大学", "https://bksy.nefu.edu.cn/index/jwxt.htm"),
        School("东北农业大学", "https://zhjwxs.neau.edu.cn"),
        School("合肥工业大学", "http://jxglstu.hfut.edu.cn/eams5-student/login"),
        School("安徽大学", "https://jw.ahu.edu.cn/"),
        School("福州大学", "https://jwch.fzu.edu.cn/"),
        School("南昌大学", "https://jwxt.ncu.edu.cn/caslogin"),
        School("太原理工大学", "http://jwc.tyut.edu.cn/"),
        School("河北工业大学", "http://jwgl.hebut.edu.cn"),
        School("内蒙古大学", "http://jwxt.imu.edu.cn/"),
        School("广西大学", "https://jwxt2018.gxu.edu.cn/jwglxt"),
        School("云南大学", "http://ehall.ynu.edu.cn/"),
        School("贵州大学", "https://jw.gzu.edu.cn"),
        School("海南大学", "https://jwxt.hainanu.edu.cn/"),
        School("宁夏大学", "https://jwgl.nxu.edu.cn/"),
        School("青海大学", "http://mh.qhu.edu.cn"),
        School("新疆大学", "http://jwxt.xju.edu.cn"),
        School("石河子大学", "https://jwgl.shzu.edu.cn"),
        School("西藏大学", "http://xsxk.utibet.edu.cn/"),
        School("西南大学", "https://one.swu.edu.cn"),
        
        // 其他省份一本高校 - 北京市
        School("首都师范大学", "http://urp.cnu.edu.cn"),
        School("北京工商大学", "https://jwgl.btbu.edu.cn"),
        School("北京工业大学", "https://jwglxt.bjut.edu.cn"),
        School("北京第二外国语学院", "https://jwcweb.bisu.edu.cn"),
        School("首都经济贸易大学", "https://jwc.cueb.edu.cn"),
        School("北方工业大学", "http://jxxx.ncut.edu.cn"),
        School("北京建筑大学", "https://jwc.bucea.edu.cn"),
        
        // 其他省份一本高校 - 上海市
        School("上海理工大学", "http://jwc.usst.edu.cn"),
        School("上海师范大学", "http://cas.shnu.edu.cn/cas/login?service=http%3A%2F%2Fcourse.shnu.edu.cn%2Feams%2Flogin.action"),
        School("上海海事大学", "http://jwxt.shmtu.edu.cn"),
        School("上海工程技术大学", "https://jwc.sues.edu.cn"),
        School("上海应用技术大学", "https://www.sit.edu.cn"),
        
        // 其他省份一本高校 - 天津市
        School("天津师范大学", "https://jwxt.tjnu.edu.cn/tjsfjw/cas/login.action"),
        School("天津工业大学", "https://jwxs.tiangong.edu.cn"),
        School("天津理工大学", "http://jwgl.tjut.edu.cn/epstar/web/swms/mainframe/homePage.jsp"),
        School("天津科技大学", "https://jw.tust.edu.cn"),
        School("天津财经大学", "https://eass.tjufe.edu.cn"),
        School("中国民航大学", "http://jwxt.cauc.edu.cn"),
        
        // 其他省份一本高校 - 江苏省
        School("扬州大学", "https://i.yzu.edu.cn"),
        School("江苏大学", "http://xuanke.ujs.edu.cn"),
        School("南京工业大学", "https://jwgl.njtech.edu.cn"),
        School("南京邮电大学", "http://jwxt.njupt.edu.cn"),
        School("南京信息工程大学", "http://jwgl.nuist.edu.cn"),
        School("南京林业大学", "https://jwxt.njfu.edu.cn"),
        School("江苏师范大学", "http://jsnujw.jsnu.edu.cn"),
        School("常州大学", "http://jwcas.cczu.edu.cn"),
        
        // 其他省份一本高校 - 浙江省
        School("浙江工业大学", "http://www.gdjw.zjut.edu.cn/jwglxt/xtgl/login_slogin.html"),
        School("浙江师范大学", "http://jwxt.zjnu.edu.cn"),
        School("杭州电子科技大学", "https://newjw.hdu.edu.cn/jwglxt/xtgl/login_slogin.html"),
        School("宁波大学", "https://jwxk.nbu.edu.cn"),
        School("浙江工商大学", "https://jwxt.zjgsu.edu.cn/jwglxt"),
        School("浙江理工大学", "https://one.zstu.edu.cn"),
        School("温州大学", "http://rz.wzu.edu.cn"),
        School("中国计量大学", "https://jwxt.cjlu.edu.cn"),
        
        // 其他省份一本高校 - 山东省
        School("山东师范大学", "http://jwxt.sdnu.edu.cn/jwglxt"),
        School("山东财经大学", "http://jw.sdufe.edu.cn"),
        School("青岛大学", "http://jw.qdu.edu.cn"),
        School("济南大学", "http://jwgl.ujn.edu.cn"),
        School("山东科技大学", "https://jwgl.sdust.edu.cn"),
        School("青岛科技大学", "https://jw.qust.edu.cn/jwglxt.htm"),
        School("烟台大学", "https://www.ytu.edu.cn"),
        School("齐鲁工业大学", "https://jw.qlu.edu.cn/jwglxt/xtgl/login_slogin.html"),
        
        // 其他省份一本高校 - 福建省
        School("华侨大学", "https://jwapp.hqu.edu.cn"),
        School("福建师范大学", "https://jwglxt.fjnu.edu.cn"),
        School("集美大学", "https://i.jmu.edu.cn"),
        
        // 其他省份一本高校 - 湖北省
        School("湖北大学", "http://jwxt.hubu.edu.cn"),
        School("武汉科技大学", "https://auth.wust.edu.cn"),
        School("中南民族大学", "http://jwxt.scuec.edu.cn"),
        School("湖北工业大学", "https://jwxt.hbut.edu.cn"),
        School("武汉工程大学", "http://jwxt.wit.edu.cn"),
        School("武汉纺织大学", "https://jwc.wtu.edu.cn"),
        School("江汉大学", "https://jwxt.jhun.edu.cn/cas/login.action"),
        School("三峡大学", "https://sxdxjwc.ctgu.edu.cn"),
        School("长江大学", "http://jwc3.yangtzeu.edu.cn"),
        
        // 其他省份一本高校 - 湖南省
        School("长沙理工大学", "http://jwgl.csust.edu.cn"),
        School("湖南科技大学", "http://kdjw.hnust.edu.cn"),
        School("湖南农业大学", "http://jwxt.hunau.edu.cn/sso.jsp"),
        School("中南林业科技大学", "https://ehall.csuft.edu.cn"),
        School("湖南工商大学", "http://jwgl.hutb.edu.cn/jsxsd/"),
        School("南华大学", "https://cas.usc.edu.cn"),
        School("吉首大学", "https://jwxt.jsu.edu.cn"),
        
        // 其他省份一本高校 - 安徽省
        School("安徽师范大学", "https://jw.ahnu.edu.cn"),
        School("安徽工业大学", "http://jwxt.ahut.edu.cn/jsxsd/"),
        School("安徽理工大学", "http://jwgl.aust.edu.cn/eams/login.action"),
        School("安徽财经大学", "https://jwcxk2.aufe.edu.cn"),
        School("安徽建筑大学", "https://jwnew.ahjzu.edu.cn/xtgl/login_slogin.html"),
        
        // 其他省份一本高校 - 江西省
        School("江西财经大学", "http://xk.jxufe.edu.cn"),
        School("华东交通大学", "https://jwxt.ecjtu.edu.cn/index.action"),
        School("江西师范大学", "https://jwc.jxnu.edu.cn/Portal/Index.aspx"),
        School("南昌航空大学", "http://jwc-publish2.jwc.nchu.edu.cn"),
        School("江西理工大学", "https://jw.jxust.edu.cn"),
        School("景德镇陶瓷大学", "https://uis.jcu.edu.cn/cas/login"),
        
        // 其他省份一本高校 - 山西省
        School("山西大学", "http://bkjw.sxu.edu.cn"),
        School("山西财经大学", "http://jwxt.sxufe.edu.cn"),
        School("山西师范大学", "http://jwcweb.sxnu.edu.cn"),
        School("太原科技大学", "https://jwc.tyust.edu.cn"),
        
        // 其他省份一本高校 - 河北省
        School("河北大学", "https://smart.hbu.edu.cn"),
        School("燕山大学", "https://ehall.ysu.edu.cn/index.html"),
        School("河北师范大学", "http://jwgl.hebtu.edu.cn"),
        School("河北农业大学", "https://jiaowu.hebau.edu.cn"),
        School("河北医科大学", "https://jwweb.hebmu.edu.cn"),
        School("石家庄铁道大学", "http://jw.stdu.edu.cn"),
        School("河北科技大学", "https://jiaowu.web.hebust.edu.cn/jwxtanz/"),
        
        // 其他省份一本高校 - 四川省
        School("西南石油大学", "https://deanservices.swpu.edu.cn/"),
        School("成都理工大学", "https://www.aao.cdut.edu.cn/"),
        School("西南科技大学", "http://jiaowu.swust.edu.cn/"),
        School("四川师范大学", "https://jwc.sicnu.edu.cn/"),
        School("成都信息工程大学", "https://jwc.cuit.edu.cn/"),
        School("西华大学", "http://jwc.xhu.edu.cn"),
        School("西南医科大学", "http://ea.swmu.edu.cn/"),
        
        // 其他省份一本高校 - 重庆市
        School("西南政法大学", "https://njwxt.swupl.edu.cn/jwglxt/xtgl/login_slogin.html"),
        School("重庆邮电大学", "https://ids.cqupt.edu.cn/authserver"),
        School("重庆交通大学", "http://jwgl.cqjtu.edu.cn/jsxsd"),
        School("重庆医科大学", "https://jiaowu.cqmu.edu.cn:8080/eams/home.action"),
        School("重庆师范大学", "http://jwxt.cqnu.edu.cn"),
        School("重庆工商大学", "https://jw.ctbu.edu.cn"),
        School("重庆理工大学", "https://ehall.cqut.edu.cn"),
        
        // 其他省份一本高校 - 陕西省
        School("西安建筑科技大学", "https://swjw.xauat.edu.cn/student/login"),
        School("西安科技大学", "http://jwportal.xust.edu.cn"),
        School("西安理工大学", "http://jwgl.xaut.edu.cn/jsxsd/"),
        School("西安石油大学", "http://jwxt.xsyu.edu.cn/eams/login.action"),
        School("西安工程大学", "http://jw.xpu.edu.cn"),
        School("陕西科技大学", "http://bkjw.sust.edu.cn/eams/login.action"),
        School("西安工业大学", "http://jwgl2018.xatu.edu.cn/"),
        School("西安邮电大学", "http://www.zfjw.xupt.edu.cn/jwglxt/xtgl/login_slogin.html"),
        School("延安大学", "https://jwglxt.yau.edu.cn/jwglxt/xtgl/login_slogin.html"),
        
        // 其他省份一本高校 - 广西
        School("广西师范大学", "https://jwjx.gxnu.edu.cn"),
        School("桂林电子科技大学", "https://www.gliet.edu.cn"),
        School("桂林理工大学", "https://jwcweb2.gltu.cn/"),
        School("广西医科大学", "https://jwxt.gxmu.edu.cn"),
        
        // 其他省份一本高校 - 云南省
        School("昆明理工大学", "http://jwctsp.kmust.edu.cn/integration/login"),
        School("云南师范大学", "http://jwmis.ynnu.edu.cn/"),
        School("云南农业大学", "https://jwxt.ynau.edu.cn/"),
        School("云南财经大学", "http://www.ynufe.edu.cn/pub/jwc/"),
        School("昆明医科大学", "http://61.166.241.221/cas/login.action"),
        
        // 其他省份一本高校 - 贵州省
        School("贵州师范大学", "http://jwgl.gznu.edu.cn/"),
        School("贵州财经大学", "http://jws.gufe.edu.cn:8001/jwglxt/xtgl/login_slogin.html"),
        School("贵州医科大学", "http://jwc.gmc.edu.cn/"),
        
        // 其他省份一本高校 - 甘肃省
        School("西北师范大学", "https://jwgl.nwnu.edu.cn/jsxsd/"),
        School("兰州理工大学", "https://jwxt.lut.edu.cn/"),
        School("兰州交通大学", "https://jiaowu.lzjtu.edu.cn/"),
        School("甘肃农业大学", "http://jwgl.gsau.edu.cn"),
        School("兰州财经大学", "https://jwxt.lzufe.edu.cn/"),
        
        // 其他省份一本高校 - 新疆
        School("新疆师范大学", "https://jwxt.xjnu.edu.cn/jsxsd/"),
        School("新疆农业大学", "https://jwxt.xjau.edu.cn/jwglxt/xtgl/login_slogin.html"),
        School("新疆医科大学", "https://jw.xjmu.edu.cn/eams/loginExt.action"),
        
        // 其他省份一本高校 - 辽宁省
        School("沈阳工业大学", "https://jwxt.sut.edu.cn"),
        School("辽宁工程技术大学", "https://jwzx.lntu.edu.cn/"),
        School("沈阳航空航天大学", "https://jwc.sau.edu.cn/"),
        School("大连交通大学", "http://jw.djtu.edu.cn/"),
        School("辽宁师范大学", "http://jwgl.lnnu.edu.cn"),
        School("沈阳师范大学", "https://jwc.synu.edu.cn/jwglxt/list.htm"),
        School("大连工业大学", "http://jwgl.dlpu.edu.cn/admin/login"),
        School("辽宁石油化工大学", "https://jwxt.lnpu.edu.cn"),
        
        // 其他省份一本高校 - 吉林省
        School("长春理工大学", "https://portal.cust.edu.cn"),
        School("东北电力大学", "https://jwc.neepu.edu.cn/"),
        School("长春工业大学", "https://jyjx.ccut.edu.cn/cas/login"),
        School("吉林农业大学", "https://jwgl.jlau.edu.cn/admin/login"),
        School("吉林师范大学", "https://jwxt.jlnu.edu.cn/jsxsd/"),
        School("长春中医药大学", "http://jwxt.ccucm.edu.cn:8080/login.aspx"),
        
        // 其他省份一本高校 - 黑龙江省
        School("哈尔滨理工大学", "http://jwzx.hrbust.edu.cn/"),
        School("东北石油大学", "http://jwgl.nepu.edu.cn/"),
        School("黑龙江大学", "http://xsxk.hlju.edu.cn/xsxk/"),
        School("哈尔滨商业大学", "https://jwgl.hrbcu.edu.cn/"),
        School("哈尔滨医科大学", "http://jwweb.hrbmu.edu.cn"),
        School("黑龙江八一农垦大学", "https://ids.byau.edu.cn/cas/login"),
        School("佳木斯大学", "http://jwc.jmsu.edu.cn/")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyToTheme(this)
        setContentView(R.layout.activity_school_list)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "选择学校"

        setupRecyclerView()
        setupApplyAdapter()
        setupCustomUrl()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun setupRecyclerView() {
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = SchoolAdapter(schools) { school ->
            openWebView(school.name, school.url)
        }
        recyclerView.adapter = adapter
    }

    private fun setupApplyAdapter() {
        val llApplyAdapter = findViewById<LinearLayout>(R.id.ll_apply_adapter)
        llApplyAdapter.setOnClickListener {
            showApplyDialog()
        }
    }

    private fun setupCustomUrl() {
        val llCustomUrl = findViewById<LinearLayout>(R.id.ll_custom_url)
        llCustomUrl.setOnClickListener {
            showCustomUrlDialog()
        }
    }

    private fun showCustomUrlDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_custom_url, null)
        val etSchoolName = dialogView.findViewById<EditText>(R.id.et_custom_school_name)
        val etUrl = dialogView.findViewById<EditText>(R.id.et_custom_url)

        StyledDialog.Builder(this)
            .title("自定义教务系统")
            .view(dialogView)
            .positiveButton("确定") {
                val schoolName = etSchoolName.text.toString().trim()
                val url = etUrl.text.toString().trim()

                if (schoolName.isEmpty()) {
                    Toast.makeText(this, "请输入学校名称", Toast.LENGTH_SHORT).show()
                    return@positiveButton
                }

                if (url.isEmpty()) {
                    Toast.makeText(this, "请输入教务系统网址", Toast.LENGTH_SHORT).show()
                    return@positiveButton
                }

                val validUrl = if (url.startsWith("http://") || url.startsWith("https://")) {
                    url
                } else {
                    "https://$url"
                }

                openWebView(schoolName, validUrl)
            }
            .negativeButton("取消")
            .show()
    }

    private fun showApplyDialog() {
        StyledDialog.Builder(this)
            .title("申请适配")
            .items(arrayOf("通过邮箱申请", "通过 GitHub Issue 申请")) { which ->
                when (which) {
                    0 -> openEmail()
                    1 -> openGitHubIssue()
                }
            }
            .negativeButton("取消")
            .show()
    }

    private fun openEmail() {
        try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:2908451607@qq.com")
                putExtra(Intent.EXTRA_SUBJECT, "申请教务系统适配")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开邮箱", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openGitHubIssue() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ClassSchedule-CourseAdapter/CourseAdapter/issues/new"))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openWebView(schoolName: String, url: String) {
        val intent = Intent(this, WebViewActivity::class.java)
        intent.putExtra("url", url)
        intent.putExtra("schoolName", schoolName)
        startActivity(intent)
    }
}

data class School(val name: String, val url: String)
