package com.att.cloud.so.cloudapi.handlers.attnetwork;
//
//import java.sql.ResultSet;
//import java.sql.SQLException;
//import java.util.List;
//
//import javax.servlet.http.HttpServletResponse;
//
//import com.att.cloud.ftpserver.utils.Logger;
//import com.att.cloud.juniper.JuniperGateWay;
//import com.att.cloud.juniper.exception.JuniperException;
//import com.att.cloud.juniper.exception.JuniperWarning;
//import com.att.cloud.so.cloudapi.messages.AttNetworkType;
//import com.att.cloud.so.cloudapi.messages.IPNetworkType;
//import com.att.cloud.so.cloudapi.messages.ServiceTypeType;
//import com.att.cloud.so.cloudapi.messages.SeverityType;
//import com.att.cloud.so.common.Errors;
//import com.att.cloud.so.dao.FirewallDAO;
import com.att.cloud.so.handlers.JAXPipelineHandler;
//import com.att.cloud.so.handlers.firewall.FirewallPolicy;
//import com.att.cloud.so.interfaces.firewall.FirewallServer;
//import com.att.cloud.so.interfaces.firewall.FirewallServers;
//import com.att.cloud.so.interfaces.ipv6.IPV6Subnet;
//import com.att.cloud.so.interfaces.ipv6.SubnetMgr;
//import com.att.cloud.so.interfaces.pipeline.PipelineEvents;
//import com.att.cloud.so.interfaces.pipeline.PipelineException;
//import com.att.cloud.so.interfaces.pipeline.PipelineMessage;
//import com.att.cloud.so.interfaces.pipeline.PipelineMessageDictionary;
////import com.att.cloud.so.interfaces.pipeline.objects.PipelineNetwork;
//import com.att.cloud.so.juniper.extended.util.CreateFirewallUtil;
//import com.att.cloud.so.juniper.extended.util.DeleteFirewallUtil;
//import com.att.cloud.so.utils.InternetSubnet;
//import com.att.cloud.so.utils.LogEngine;
//import com.att.cloud.so.utils.Subnet;
//import com.att.cloud.so.utils.Tools;
//
///* @author nd0563 [Naren Deshpande] */
//
public class AddFWIPv6ConfigurationHandler extends JAXPipelineHandler {

//	******** CURRENTLY THIS HANDLER IS NOT NEEDED AND NOT USED ********* 
	
	
//	private String siteId, customerId;
//	private Subnet subnet;
//	private IPV6Subnet ipv6Subnet;
//	private SubnetMgr subnetMgr;
//	private int vdcId, csId, vlanId, cnId;
//	private String orgid;
//	private boolean dualStack;
//	protected List<FirewallPolicy> policyList;
//	protected static String ZONE_INTERNET = "Internet";
//	private boolean createdInDB;
//	private boolean createdInDevice;
//	boolean updateV6;
//	FirewallServers firewallServers = null;
//	FirewallDAO firewallDao = new FirewallDAO();
//
//	@Override
//	public void execute(PipelineMessage pmsg) throws PipelineException {
//			AttNetworkType attnetwork = (AttNetworkType)pmsg.getAttribute("attnetwork");
//			boolean useFW = attnetwork.isUseFw();
//			
//			dualStack = IPNetworkType.IP_V_4_V_6.value().equals(attnetwork.getNetworkType().value());
//			long time = System.currentTimeMillis();
//			JuniperGateWay juniperGateWay = new JuniperGateWay(Logger.getSrxlog());
//			try {
//			if (!useFW)
//			{
//				LogEngine.debug("UseFW  is false. nothing to do in CreateFirewallConfigurationHandler");
//				return;
//			}
//			else
//			{
//				if (IPNetworkType.IP_V_4.value().equals(attnetwork.getNetworkType().value()) && dualStack) {
//					throw new Exception("Invalid request. attnetwork="+attnetwork+" and dualStack="+dualStack);
//				}
//				if (IPNetworkType.IP_V_4_V_6.value().equals(attnetwork.getNetworkType().value()) && dualStack) {		//checking dulaStack for ipv6
//					LogEngine.info("Creating both IP_V_4 and IP_V_4_V_6 configuration for this Network");
//				}
//			}
//			initDba();
//			
//			String subnetAddress= attnetwork.getSubnetAddress();
//			int subnetPrefix = attnetwork.getSubnetPrefix();
//			
//			int cnId = pmsg.getAttributeAsInt("networkId");
//			//Gets VDCID and CSID
//			getVDCdetails(cnId);
//			
//			vlanId = getvLanFromAVI(cnId);
//			siteId = pmsg.getAttributeAsStr("siteid");
//			orgid = pmsg.getAttributeAsStr("orgId");
//			customerId = pmsg.getAttributeAsStr(PipelineMessageDictionary.CUSTOMER);
//			//PipelineNetwork pNetwork = new PipelineNetwork(cnId, orgid, siteId);
//
//
//			//subnetMgr = new SubnetMgr();
//			LogEngine.info("Get IPV6Subnet for the Customer " + orgid	+ " ,Site " + siteId);
//			Tools.log("99999 Before pNetwork.getIpv6Subnet()");
//			//ipv6Subnet = pNetwork.getIpv6Subnet();
//			Tools.log("99999 After  pNetwork.getIpv6Subnet()" + ipv6Subnet);
//			ipv6Subnet = (IPV6Subnet)pmsg.getAttribute("ipv6Subnet");
//			LogEngine.debug("ipv6Subnet in ADDFWCONFIG  " + ipv6Subnet);
//			String service = attnetwork.getServiceType().name();
//			Tools.log("99999 After  Service " + service);
//			
//			if(ServiceTypeType.AVPN.value().equals( service )) {
//				LogEngine.debug( "AVPN Service. Firewall will not be configured" );
//				return;
//			}
//
//			LogEngine.debug("[cnId : "+cnId + " siteId : "+siteId + " customerId : "+customerId + " vlanId : "+vlanId + " customerSubnetId : "+csId+"]");			
//			policyList = firewallDao.getDefaultFirewallPolicies();
//			
//			subnet = new InternetSubnet( subnetAddress + "/" + subnetPrefix );
//			
//			firewallServers = new FirewallServers();
//			for(FirewallServer firewallServer : firewallServers.getServers( siteId )) {
//				try {    		
//					LogEngine.debug("99999 Start of loop ");
//					juniperGateWay.connect( firewallServer.getRecord() );
//					LogEngine.debug("99999 Firewall connected ");
//					juniperGateWay.openConfigurationInPrivateMode();
//					LogEngine.debug("99999 Configuration Opened ");
//			        juniperGateWay.applyFilters( 
//			        		CreateFirewallUtil.addConfigurationToSRX(firewallServer, service, juniperGateWay, policyList, customerId, vlanId, cnId, subnet, ipv6Subnet, dualStack) );
//			        LogEngine.debug("99999 Filter Applied ");
//			        juniperGateWay.applyCommitFilter();
//			        LogEngine.debug("99999 Commit Filter Done ");
//		        	createdInDevice = true;
//		        } catch (JuniperException e) {
//		        	juniperGateWay.discardChanges();
//		        	LogEngine.logException( e);
//		        	createdInDevice = false;
//		        	throw new Exception("There was an exception while executing the rules in firewall. " +
//								"Please try again after 2 minutes, if the error persists contact system administrator");
//		        } catch (JuniperWarning e) {
//			        LogEngine.logException( e);
//		        } catch (Exception e) {
//		        	juniperGateWay.discardChanges();
//		        	LogEngine.logException( e);
//		        	createdInDevice = false;
//		        	throw new Exception("There was an exception while executing the rules in firewall. " +
//								"Please try again after 2 minutes, if the error persists contact system administrator");
//		        }
//    			addConfigurationToDB(firewallServer.getServerid(), cnId);
//    			createdInDB= true;
//			}
//			
//			LogEngine.debug( "Time taken to create Firewll Configuration and createdInDB = : "+Tools.getTimeDifferenceInSeconds( time ) + createdInDB );
//		} catch(Exception e) {
//			Errors.logAlarm("ADDVCD001", e.getMessage());
//			LogEngine.logException( e);
//			triggerRollback();	
//			pmsg.getResponse().addErrmsg(e.getMessage(), HttpServletResponse.SC_INTERNAL_SERVER_ERROR , SeverityType.FATAL,  e.getClass().getName());	
//			this.setAbortPipeline(true);
//		} finally{
//			firewallDao.close();
//			time = System.currentTimeMillis();
//        	LogEngine.debug("DB : "+ Tools.getTimeDifferenceInSeconds(time) + " seconds");
//        	try {
//    			juniperGateWay.closeConfiguration();
//    			juniperGateWay.disconnect();
//        	} catch (Exception exception) {
//        		LogEngine.logException( exception);
//        		LogEngine.srxLog( exception.getMessage() );
//        	}
//		}
//	}
//	@Override
//	public PipelineEvents handleEventType() {
//		return PipelineEvents.SERVICE_ATTNETWORK_PUT;
//	}
//	
//	private void addConfigurationToDB(int serverId, int cnId) throws SQLException {
//		int fwid = firewallDao.addApplianceToDB(serverId, customerId, csId, cnId, dualStack, subnet, ipv6Subnet);
//		firewallDao.addPoliciesToDB( fwid, policyList );
//	}
//	
//	@Override
//	public void rollback() {
//		JuniperGateWay juniperGateWay = new JuniperGateWay(Logger.getSrxlog());
//		firewallDao = new FirewallDAO();
//		try {
//			LogEngine.debug("In Extension API Rollback: CreateFirewallConfigurationHandler, SiteId is "+ siteId);
//			LogEngine.debug("In Extension API Rollback: CreateFirewallConfigurationHandler, createdInDevice is "+ createdInDevice);
//			LogEngine.debug("In Extension API Rollback: CreateFirewallConfigurationHandler, createdInDB is "+ createdInDB);
//			if(firewallServers == null) {
//				LogEngine.debug("In Extension API Rollback: CreateFirewallConfigurationHandler, FirewallServers is Null");
//				firewallServers = new FirewallServers();
//			}
//			
//			
//			for(FirewallServer firewallServer : firewallServers.getServers( siteId )) {
//				if(createdInDevice) {					
//					try {
//						juniperGateWay.connect( firewallServer.getRecord() );						
//						juniperGateWay.openConfigurationInPrivateMode();				
//						juniperGateWay.applyFilters( 
//								DeleteFirewallUtil.removeConfigurationFromSRX(firewallServer, juniperGateWay, customerId, vdcId, vlanId, cnId, dualStack));
//						juniperGateWay.applyCommitFilter();
//					} catch (JuniperException e) {
//						juniperGateWay.discardChanges();
//						LogEngine.logException( e);
//						throw e;
//					} catch (JuniperWarning e) {
//						LogEngine.logException( e);
//					} catch (Exception e) {
//						juniperGateWay.discardChanges();
//						LogEngine.logException( e);
//						throw e;
//					}
//				}
//				if(createdInDB) {
//					firewallDao.removeConfigurationFromDB(firewallServer, csId, cnId);
//				}
//				if (updateV6) {
//					//TODO: 
//				}
//			}
//		} catch (Exception exception) {
//			LogEngine.logException( exception);
//			Errors.logAlarm("ADDVCD001", "Error in Create Firewall Rollback: "+exception.getMessage());
//		} finally {
//			firewallDao.close();
//			try {
//    			juniperGateWay.closeConfiguration();
//    			juniperGateWay.disconnect();
//        	} catch (Exception exception) {
//        		LogEngine.logException( exception);
//        		LogEngine.srxLog( exception.getMessage() );
//        	}
//		}
//	}
//	
//	private static final String GET_VDCID = "select vdc.vdcid, vdc.csid, cn.service from customer_network cn, cs_inventory cs, vdc where vdc.csid=cs.csid and cs.ippid=cn.ippid and cn.cnid=?";
//	public void getVDCdetails(int cnId){
//		ResultSet rs = null;
//		try{
//			dba.prepareStatement(GET_VDCID);
//			dba.setInt(1, cnId);
//			rs = dba.executeQuery();
//			if(rs!=null && rs.next()){
//				vdcId = rs.getInt("vdcid");
//				csId = rs.getInt("csid");
//			}
//		}catch(Exception e){
//			LogEngine.logException( e);
//			LogEngine.debug("Unable to get VDCID from DB . . . " + e.getCause());
//			LogEngine.logException( e);
//		}finally{
//			dba.releasePStmt();
//			dba.closeRS(rs);
//		}
//	}
//	
//	private static final String GET_VLANID = "select lbavi from avi, customer_network cn where avi.aviid=cn.aviid and cn.cnid=?";
//	public int getvLanFromAVI(int cnId){
//		ResultSet rs = null;
//		try{
//			dba.prepareStatement(GET_VLANID);
//			dba.setInt(1, cnId);
//			rs = dba.executeQuery();
//			if(rs != null && rs.next()){
//				return rs.getInt("lbavi");
//			}
//		}catch(Exception e){
//			LogEngine.logException( e);
//			LogEngine.debug("Unable to get data from DB.." + e.getCause());
//			LogEngine.logException( e);
//		}finally{
//			dba.releasePStmt();
//			dba.closeRS(rs);
//		}
//		return -1;
//	}
}
