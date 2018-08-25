package com.xnx3.wangmarket.plugin.bbs.controller;

import java.awt.Font;
import java.io.IOException;
import java.util.List;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import com.xnx3.DateUtil;
import com.xnx3.Lang;
import com.xnx3.StringUtil;
import com.xnx3.exception.NotReturnValueException;
import com.xnx3.j2ee.Func;
import com.xnx3.j2ee.Global;
import com.xnx3.j2ee.entity.SmsLog;
import com.xnx3.j2ee.entity.User;
import com.xnx3.j2ee.func.ActionLogCache;
import com.xnx3.j2ee.func.AttachmentFile;
import com.xnx3.j2ee.func.Captcha;
import com.xnx3.j2ee.func.Log;
import com.xnx3.j2ee.service.SmsLogService;
import com.xnx3.j2ee.service.SqlService;
import com.xnx3.j2ee.service.UserService;
import com.xnx3.j2ee.shiro.ShiroFunc;
import com.xnx3.j2ee.vo.BaseVO;
import com.xnx3.media.CaptchaUtil;
import com.xnx3.wangmarket.admin.G;
import com.xnx3.wangmarket.admin.bean.UserBean;
import com.xnx3.wangmarket.admin.entity.Site;
import com.xnx3.wangmarket.admin.util.AliyunLog;
import com.xnx3.wangmarket.admin.vo.SiteVO;
import com.xnx3.wangmarket.superadmin.entity.Agency;

/**
 * 论坛登录、注册
 * @author 管雷鸣
 */
@Controller
@RequestMapping("/plugin/bbs/")
public class LoginBbsPluginController_ extends com.xnx3.wangmarket.admin.controller.BaseController {
	@Resource
	private UserService userService;
	@Resource
	private SmsLogService smsLogService;
	@Resource
	private SqlService sqlService;
	
	

	/**
	 * 注册页面 
	 */
	@RequestMapping("/reg${url.suffix}")
	public String reg(HttpServletRequest request ,Model model){
//		if(Global.getInt("ALLOW_USER_REG") == 0){
//			return error(model, "系统已禁止用户自行注册");
//		}
//		//判断用户是否已注册，已注册的用户将出现提示，已登录，无需注册
		if(getUser() != null){
			return error(model, "您已登陆，无需注册", "plugin/bbs/index.do");
		}
		
		userService.regInit(request);
		ActionLogCache.insert(request, "进入论坛注册页面");
		return "plugin/bbs/reg";
	}
	
	

	/**
	 * 用户开通账户并创建网站，进行提交保存
	 * @param username 用户名
	 * @param email 邮箱，可为空
	 * @param password 密码
	 */
	@RequestMapping(value="regSubmit${url.suffix}", method = RequestMethod.POST)
	@ResponseBody
	public BaseVO regSubmit(HttpServletRequest request,
			@RequestParam(value = "username", required = false , defaultValue="") String username,
			@RequestParam(value = "email", required = false , defaultValue="") String email,
			@RequestParam(value = "password", required = false , defaultValue="") String password
			){
//		if(Global.getInt("ALLOW_USER_REG") == 0){
//			return error("抱歉，当前禁止用户自行注册开通网站！");
//		}
		
		//注册用户
		User user = new User();
		user.setUsername(filter(username));
		user.setEmail(filter(email));
		user.setPassword(password);
		user.setOssSizeHave(0);
		user.setAuthority(com.xnx3.wangmarket.plugin.bbs.Global.BBS_USER_ROLE_ID+"");
		BaseVO userVO = userService.reg(user, request);
		if(userVO.getResult() - BaseVO.FAILURE == 0){
			return userVO;
		}
		
		//为此用户设置其自动登录成功
		int userid = Lang.stringToInt(userVO.getInfo(), 0);
		if(userid == 0){
			ActionLogCache.insert(request, "warn", "注册论坛出现问题，info:"+userVO.getInfo());
			return error(userVO.getInfo());
		}
		BaseVO loginVO = userService.loginByUserid(request,userid);
		if(loginVO.getResult() - BaseVO.FAILURE == 0){
			return loginVO;
		}
		ShiroFunc.getCurrentActiveUser().setObj(new UserBean());
		
		return success();
	}
	
	
	/**
	 * 登陆页面
	 */
	@RequestMapping("login${url.suffix}")
	public String login(HttpServletRequest request,Model model){
		//检测 MASTER_SITE_URL、 ATTACHMENT_FILE_URL 是否已经设置，即是否已经install安装了
//		if(Global.get("MASTER_SITE_URL") == null || Global.get("MASTER_SITE_URL").length() == 0 || Global.get("ATTACHMENT_FILE_URL") == null || Global.get("ATTACHMENT_FILE_URL").length() == 0){
//			return error(model, "监测到您尚未安装系统！请先根据提示进行安装", "install/index.do");
//		}
		
		if(getUser() != null){
			ActionLogCache.insert(request, "论坛已登陆", "已经登录，无需再登录，进行跳转");
			//重定向到论坛首页
			return redirect("plugin/bbs/index.do");
		}
		
		ActionLogCache.insert(request, "进入论坛登录页面");
		return "plugin/bbs/login";
	}

	/**
	 * 登陆请求验证
	 * @param request {@link HttpServletRequest} 
	 * 		<br/>登陆时form表单需提交三个参数：username(用户名/邮箱)、password(密码)、code（图片验证码的字符）
	 * @return vo.result:
	 * 			<ul>
	 * 				<li>0:失败</li>
	 * 				<li>1:成功</li>
	 * 			</ul>
	 */
	@RequestMapping("loginSubmit${url.suffix}")
	@ResponseBody
	public BaseVO loginSubmit(HttpServletRequest request,Model model){
		//验证码校验
		BaseVO capVO = Captcha.compare(request.getParameter("code"), request);
		if(capVO.getResult() == BaseVO.FAILURE){
			ActionLogCache.insert(request, "论坛用户名密码模式登录失败", "验证码出错，提交的验证码："+StringUtil.filterXss(request.getParameter("code")));
			return capVO;
		}else{
			//验证码校验通过
			
			BaseVO baseVO =  userService.loginByUsernameAndPassword(request);
			if(baseVO.getResult() == BaseVO.SUCCESS){
				//登录成功,BaseVO.info字段将赋予成功后跳转的地址，所以这里要再进行判断
				
				//用于缓存入Session，用户的一些基本信息，比如用户的站点信息、用户的上级代理信息、如果当前用户是代理，还包含当前用户的代理信息等
				UserBean userBean = new UserBean();
				
				//得到当前登录的用户的信息
				User user = getUser();
				//可以根据用户的不同权限，来判断用户登录成功后要跳转到哪个页面
				if(Func.isAuthorityBySpecific(user.getAuthority(), Global.get("ROLE_SUPERADMIN_ID"))){
					//如果是超级管理员，那么跳转到管理后台
					baseVO.setInfo("admin/index/index.do");
					ActionLogCache.insert(request, "用户名密码模式登录成功","进入管理后台admin/index/");
				}else{
					/* 自己扩展的 */
					
					/**** 代理相关判断，将其推荐人(上级代理)加入Shiro存储起来 ***/
					if(user.getReferrerid() == null || user.getReferrerid() == 0){
						//网站用户没有发现上级代理，理论上这是不成立的，网站必须是有代理平台开通，这里暂时先忽略
					}else{
						//得到上级的代理信息
						Agency parentAgency = sqlService.findAloneBySqlQuery("SELECT * FROM agency WHERE userid = " + getUser().getReferrerid(), Agency.class);
						userBean.setParentAgency(parentAgency);
					}
					
					//当前时间
					int currentTime = DateUtil.timeForUnix10();	
					
					//判断当前用户的权限，是代理还是网站使用者
					if(Func.isAuthorityBySpecific(user.getAuthority(), Global.get("ROLE_USER_ID"))){
						//普通用户，建站用户，网站使用者
						
						//得到当前用户站点的相关信息，加入userBean，以存入Session缓存起来
						Site site = sqlService.findAloneBySqlQuery("SELECT * FROM site WHERE userid = "+getUserId()+" ORDER BY id DESC", Site.class);
						if(site != null){
							userBean.setSite(site);
						}
						
						//判断网站用户是否是已过期，使用期满，将无法使用
						if(site != null && site.getExpiretime() != null && site.getExpiretime() < currentTime){
							//您的网站已到期。若要继续使用，请续费
							BaseVO vo = new BaseVO();
							String info = "";
							try {
								info = "您的网站已于 "+DateUtil.dateFormat(site.getExpiretime(), "yyyy-MM-dd")+" 到期！"
										+ "<br/>若要继续使用，请联系："
										+ "<br/>姓名："+userBean.getParentAgency().getName()
										+ "<br/>QQ："+userBean.getParentAgency().getQq()
										+ "<br/>电话："+userBean.getParentAgency().getPhone();
							} catch (NotReturnValueException e) {
								e.printStackTrace();
							}
							vo.setBaseVO(11, info);
							ShiroFunc.getCurrentActiveUser().setObj(null);  	//清空 Session信息
							return vo;
						}
						
						//计算其网站所使用的资源，比如OSS已占用了多少资源
						loginSuccessComputeUsedResource();
						
						ActionLogCache.insert(request, "用户名密码模式登录成功","进入网站管理后台");
					}else{
						//代理
						
						//得到当前用户代理的相关信息，加入userBean，以存入Session缓存起来
						Agency myAgency = sqlService.findAloneBySqlQuery("SELECT * FROM agency WHERE userid = " + getUserId(), Agency.class);
						userBean.setMyAgency(myAgency);
						
						//判断当前代理是否是已过期，使用期满，将无法登录
						if (myAgency != null && myAgency.getExpiretime() != null && myAgency.getExpiretime() < currentTime){
							//您的代理资格已到期。若要继续使用，请联系您的上级
							BaseVO vo = new BaseVO();
							String info = "";
							try {
								info = "您的代理资格已于 "+DateUtil.dateFormat(myAgency.getExpiretime(), "yyyy-MM-dd")+" 到期！"
										+ "<br/>若要继续使用，请联系："
										+ "<br/>姓名："+userBean.getParentAgency().getName()
										+ "<br/>QQ："+userBean.getParentAgency().getQq()
										+ "<br/>电话："+userBean.getParentAgency().getPhone();
							} catch (NotReturnValueException e) {
								e.printStackTrace();
							}
							vo.setBaseVO(11, info);
							ShiroFunc.getCurrentActiveUser().setObj(null);  	//清空 Session信息
							return vo;
						}
						
						ActionLogCache.insert(request, "用户名密码模式登录成功","进入代理后台");
					}
					
					//设置登录成功后，是跳转到总管理后台、代理后台，还是跳转到wap、pc、cms
					baseVO.setInfo(com.xnx3.wangmarket.admin.Func.getConsoleRedirectUrl());	
				}
				
				//将用户相关信息加入Shiro缓存
				ShiroFunc.getCurrentActiveUser().setObj(userBean);
			}else{
				ActionLogCache.insert(request, "用户名密码模式登录失败",baseVO.getInfo());
			}
			
			return baseVO;
		}
	}


	/**
	 * 用户登陆成功后，计算其所使用的资源，如OSS占用
	 * <br/>1.计算用户空间大小
	 * <br/>2.设定用户是否可进行上传附件、图片
	 */
	public void loginSuccessComputeUsedResource(){
		//获取其下有多少网站
		final List<Site> list = sqlService.findBySqlQuery("SELECT * FROM site WHERE userid = "+getUserId(), Site.class);
		
		//如果这个用户是单纯的网站用户，并且今天并没有过空间计算，那么就要计算其所有的空间了
		final String currentDate = DateUtil.currentDate("yyyyMMdd");
	
		
		if((getUser().getOssUpdateDate() == null) || (getUser().getAuthority().equals(Global.get("USER_REG_ROLE")) && !getUser().getOssUpdateDate().equals(currentDate))){
			//计算当前用户下面有多少站点，每个站点的OSS的news文件夹下用了多少存储空间了
			new Thread(new Runnable() {
				public void run() {
					
					//属于该用户的这些网站共占用了多少存储空间去
					long sizeB = 0;
					try {
						for (int i = 0; i < list.size(); i++) {
							sizeB += AttachmentFile.getDirectorySize("site/"+list.get(i).getId()+"/");
						}
					} catch (Exception e) {
						e.printStackTrace();
						System.out.println("你应该是还没配置开通OSS吧~~要想上传图片上传附件，还是老老实实，访问 /instal/index.do 进行安装吧");
					}
					int kb = Math.round(sizeB/1024);
					sqlService.executeSql("UPDATE user SET oss_update_date = '"+currentDate+"' , oss_size = "+kb+" WHERE id = "+getUserId());
					ShiroFunc.getUser().setOssSize(kb);
					
					ShiroFunc.setUEditorAllowUpload(kb<ShiroFunc.getUser().getOssSizeHave()*1000);
				}
			}).start();
		}else{
			ShiroFunc.setUEditorAllowUpload(getUser().getOssSize()<ShiroFunc.getUser().getOssSizeHave()*1000);
		}
	}
	
	
	
}