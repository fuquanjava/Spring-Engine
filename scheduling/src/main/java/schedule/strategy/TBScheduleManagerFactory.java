package schedule.strategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Timer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import com.taobao.pamirs.schedule.ConsoleManager;
import com.taobao.pamirs.schedule.IScheduleTaskDeal;
import com.taobao.pamirs.schedule.ScheduleUtil;
import com.taobao.pamirs.schedule.taskmanager.IScheduleDataManager;
import com.taobao.pamirs.schedule.taskmanager.TBScheduleManagerStatic;
import com.taobao.pamirs.schedule.zk.ScheduleDataManager4ZK;
import com.taobao.pamirs.schedule.zk.ScheduleStrategyDataManager4ZK;
import com.taobao.pamirs.schedule.zk.ZKManager;

/**
 * 调度服务器构造器
 *
 * @author xuannan
 *
 */
public class TBScheduleManagerFactory implements ApplicationContextAware {
	protected static transient Log logger = LogFactory.getLog(TBScheduleManagerFactory.class);

	private Map<String,String> zkConfig;

	protected ZKManager zkManager;

	/**
	 * 是否启动调度管理，如果只是做系统管理，应该设置为false
	 */
	public boolean start = true;
	private int timerInterval = 2000;

	/**
	 * 调度配置中心客服端
	 */
	private IScheduleDataManager	scheduleDataManager;
	private ScheduleStrategyDataManager4ZK scheduleStrategyManager;

	private Map<String,List<IStrategyTask>> managerMap = new ConcurrentHashMap<String, List<IStrategyTask>>();

	private ApplicationContext			applicationcontext;
	private String uuid;
	private String ip;
	private String hostName;

	private Timer timer;
	private ManagerFactoryTimerTask timerTask;
	protected Lock  lock = new ReentrantLock();

	volatile String  errorMessage ="No config Zookeeper connect infomation";
	private InitialThread initialThread;

	public TBScheduleManagerFactory() {
		this.ip = ScheduleUtil.getLocalIP();
		this.hostName = ScheduleUtil.getLocalHostName();
	}

	public void init() throws Exception {
		Properties properties = new Properties();
		for(Map.Entry<String,String> e: this.zkConfig.entrySet()){
			properties.put(e.getKey(),e.getValue());
		}
		this.init(properties);
	}

	public void reInit(Properties p) throws Exception{
		if(this.start || this.timer != null || this.managerMap.size() >0){
			throw new Exception("调度器有任务处理，不能重新初始化");
		}
		this.init(p);
	}

	public void init(Properties p) throws Exception {
		if(this.initialThread != null){
			this.initialThread.stopThread();
		}
		this.lock.lock();
		try{
			this.scheduleDataManager = null;
			this.scheduleStrategyManager = null;
			ConsoleManager.setScheduleManagerFactory(this);
			if(this.zkManager != null){
				this.zkManager.close();
			}
			this.zkManager = new ZKManager(p);
			this.errorMessage = "Zookeeper connecting ......" + this.zkManager.getConnectStr();
			initialThread = new InitialThread(this);
			initialThread.setName("TBScheduleManagerFactory-initialThread");
			initialThread.start();
		}finally{
			this.lock.unlock();
		}
	}

	/**
	 * 在Zk状态正常后回调数据初始化
	 * @throws Exception
	 */
	public void initialData() throws Exception{
		this.zkManager.initial();
		this.scheduleDataManager = new ScheduleDataManager4ZK(this.zkManager);
		this.scheduleStrategyManager  = new ScheduleStrategyDataManager4ZK(this.zkManager);
		if (this.start) {
			// 注册调度管理器
			this.scheduleStrategyManager.registerManagerFactory(this);
			if(timer == null){
				timer = new Timer("TBScheduleManagerFactory-Timer");
			}
			if(timerTask == null){
				timerTask = new ManagerFactoryTimerTask(this);
				timer.schedule(timerTask, 2000,this.timerInterval);
			}
		}
	}

	/**
	 * 创建调度服务器
	 * @param strategy
	 * @return
	 * @throws Exception
	 */
	public IStrategyTask createStrategyTask(ScheduleStrategy strategy)
			throws Exception {
		IStrategyTask result = null;
		if(ScheduleStrategy.Kind.Schedule == strategy.getKind()){
			String baseTaskType = ScheduleUtil.splitBaseTaskTypeFromTaskType(strategy.getTaskName());
			String ownSign =ScheduleUtil.splitOwnsignFromTaskType(strategy.getTaskName());
			result = new TBScheduleManagerStatic(this,baseTaskType,ownSign,scheduleDataManager);
		}else if(ScheduleStrategy.Kind.Java == strategy.getKind()){
			result=(IStrategyTask)Class.forName(strategy.getTaskName()).newInstance();
			result.initialTaskParameter(strategy.getTaskParameter());
		}
		return result;
	}

	public void refresh() throws Exception {
		this.lock.lock();
		try {
			// 判断状态是否终止
			ManagerFactoryInfo stsInfo = null;
			boolean isException = false;
			try {
				stsInfo = this.getScheduleStrategyManager().loadManagerFactoryInfo(this.getUuid());
			} catch (Exception e) {
				isException = true;
				logger.error(e.getMessage(), e);
			}
			if (isException == true) {
				try {
					stopServer(null); // 停止所有的调度任务
					this.getScheduleStrategyManager().unRregisterManagerFactory(this);
				} finally {
					reRegisterManagerFactory();
				}
			} else if (! stsInfo.isStart()) {
				stopServer(null); // 停止所有的调度任务
				this.getScheduleStrategyManager().unRregisterManagerFactory(
						this);
			} else {
				reRegisterManagerFactory();
			}
		} finally {
			this.lock.unlock();
		}
	}
	public void reRegisterManagerFactory() throws Exception{
		//重新分配调度器
		List<String> stopList = this.getScheduleStrategyManager().registerManagerFactory(this);
		for (String strategyName : stopList) {
			this.stopServer(strategyName);
		}
		this.assignScheduleServer();
		this.reRunScheduleServer();
	}
	/**
	 * 根据策略重新分配调度任务的机器
	 * @throws Exception
	 */
	public void assignScheduleServer() throws Exception{
		for(ScheduleStrategyRunntime run: this.scheduleStrategyManager.loadAllScheduleStrategyRunntimeByUUID(this.uuid)){
			List<ScheduleStrategyRunntime> factoryList = this.scheduleStrategyManager.loadAllScheduleStrategyRunntimeByTaskType(run.getStrategyName());
			if(factoryList.size() == 0 || ! this.isLeader(this.uuid, factoryList)){
				continue;
			}
			ScheduleStrategy scheduleStrategy =this.scheduleStrategyManager.loadStrategy(run.getStrategyName());

			int[] nums =  ScheduleUtil.assignTaskNumber(factoryList.size(), scheduleStrategy.getAssignNum(), scheduleStrategy.getNumOfSingleServer());
			for(int i=0;i<factoryList.size();i++){
				ScheduleStrategyRunntime factory = 	factoryList.get(i);
				//更新请求的服务器数量
				this.scheduleStrategyManager.updateStrategyRunntimeReqestNum(run.getStrategyName(),
						factory.getUuid(),nums[i]);
			}
		}
	}

	public boolean isLeader(String uuid, List<ScheduleStrategyRunntime> factoryList) {
		long no = Long.parseLong(uuid.substring(uuid.lastIndexOf("$") + 1));
		for (ScheduleStrategyRunntime server : factoryList) {
			if (no > Long.parseLong(server.getUuid().substring(
					server.getUuid().lastIndexOf("$") + 1))) {
				return false;
			}
		}
		return true;
	}
	public void reRunScheduleServer() throws Exception{
		for (ScheduleStrategyRunntime run : this.scheduleStrategyManager.loadAllScheduleStrategyRunntimeByUUID(this.uuid)) {
			List<IStrategyTask> list = this.managerMap.get(run.getStrategyName());
			if(list == null){
				list = new ArrayList<IStrategyTask>();
				this.managerMap.put(run.getStrategyName(),list);
			}
			while(list.size() > run.getRequestNum() && list.size() >0){
				IStrategyTask task  =  list.remove(list.size() - 1);
				try {
					task.stop();
				} catch (Throwable e) {
					logger.error("注销任务错误：" + e.getMessage(), e);
				}
			}
			//不足，增加调度器
			ScheduleStrategy strategy = this.scheduleStrategyManager.loadStrategy(run.getStrategyName());
			while(list.size() < run.getRequestNum()){
				IStrategyTask result = this.createStrategyTask(strategy);
				list.add(result);
			}
		}
	}

	/**
	 * 终止一类任务
	 *
	 * @param strategyName
	 * @throws Exception
	 */
	public void stopServer(String strategyName) throws Exception {
		if(strategyName == null){
			String[] nameList = (String[])this.managerMap.keySet().toArray(new String[0]);
			for (String name : nameList) {
				for (IStrategyTask task : this.managerMap.get(name)) {
					try{
						task.stop();
					}catch(Throwable e){
						logger.error("注销任务错误："+e.getMessage(),e);
					}
				}
				this.managerMap.remove(name);
			}
		}else {
			List<IStrategyTask> list = this.managerMap.get(strategyName);
			if(list != null){
				for(IStrategyTask task:list){
					try {
						task.stop();
					} catch (Throwable e) {
						logger.error("注销任务错误：" + e.getMessage(), e);
					}
				}
				this.managerMap.remove(strategyName);
			}

		}
	}
	/**
	 * 重启所有的服务
	 * @throws Exception
	 */
	public void reStart() throws Exception {
		try {
			if (this.timer != null) {
				if(this.timerTask != null){
					this.timerTask.cancel();
					this.timerTask = null;
				}
				this.timer.purge();
			}
			this.stopServer(null);
			this.zkManager.close();
			this.uuid = null;
			this.init();
		} catch (Throwable e) {
			logger.error("重启服务失败：" + e.getMessage(), e);
		}
	}
	public boolean isZookeeperInitialSucess() throws Exception{
		return this.zkManager.checkZookeeperState();
	}
	public String[] getScheduleTaskDealList() {
		return applicationcontext.getBeanNamesForType(IScheduleTaskDeal.class);

	}

	public IScheduleDataManager getScheduleDataManager() {
		if(this.scheduleDataManager == null){
			throw new RuntimeException(this.errorMessage);
		}
		return scheduleDataManager;
	}
	public ScheduleStrategyDataManager4ZK getScheduleStrategyManager() {
		if(this.scheduleStrategyManager == null){
			throw new RuntimeException(this.errorMessage);
		}
		return scheduleStrategyManager;
	}

	public void setApplicationContext(ApplicationContext aApplicationcontext) throws BeansException {
		applicationcontext = aApplicationcontext;
	}

	public Object getBean(String beanName) {
		return applicationcontext.getBean(beanName);
	}
	public String getUuid() {
		return uuid;
	}

	public String getIp() {
		return ip;
	}

	public void setUuid(String uuid) {
		this.uuid = uuid;
	}

	public String getHostName() {
		return hostName;
	}

	public void setStart(boolean isStart) {
		this.start = isStart;
	}

	public void setTimerInterval(int timerInterval) {
		this.timerInterval = timerInterval;
	}
	public void setZkConfig(Map<String,String> zkConfig) {
		this.zkConfig = zkConfig;
	}

	public Map<String,String> getZkConfig() {
		return zkConfig;
	}
}

class ManagerFactoryTimerTask extends java.util.TimerTask {
	private static transient Log log = LogFactory.getLog(ManagerFactoryTimerTask.class);
	TBScheduleManagerFactory factory;
	int count =0;

	public ManagerFactoryTimerTask(TBScheduleManagerFactory aFactory) {
		this.factory = aFactory;
	}

	public void run() {
		try {
			Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
			if(! this.factory.zkManager.checkZookeeperState()){
				if(count > 5){
					log.error("Zookeeper连接失败，关闭所有的任务后，重新连接Zookeeper服务器......");
					this.factory.reStart();

				}else{
					count = count + 1;
				}
			}else{
				count = 0;
				this.factory.refresh();
			}
		} catch (Exception ex) {
			log.error(ex.getMessage(), ex);
		}
	}
}

class InitialThread extends Thread{
	private static transient Log log = LogFactory.getLog(InitialThread.class);
	TBScheduleManagerFactory facotry;
	boolean isStop = false;
	public InitialThread(TBScheduleManagerFactory aFactory){
		this.facotry = aFactory;
	}
	public void stopThread(){
		this.isStop = true;
	}
	@Override
	public void run() {
		facotry.lock.lock();
		try {
			int count =0;
			while(! facotry.zkManager.checkZookeeperState()){
				count = count + 1;
				if(count % 50 == 0){
					facotry.errorMessage = "Zookeeper connecting ......" + facotry.zkManager.getConnectStr() + " spendTime:" + count * 20 +"(ms)";
					log.error(facotry.errorMessage);
				}
				Thread.sleep(20);
				if(this.isStop){
					return;
				}
			}
			facotry.initialData();
		} catch (Throwable e) {
			log.error(e.getMessage(),e);
		}finally{
			facotry.lock.unlock();
		}

	}

}