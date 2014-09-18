package com.att.cloud.so.cloudapi.handlers.attnetwork;
//
//import iControl.LocalLBLBMethod;
//
//import java.sql.ResultSet;
//import java.util.ArrayList;
//import java.util.List;
//
//import javax.servlet.http.HttpServletResponse;
//
//import com.att.cloud.so.cloudapi.messages.AttNetworkType;
//import com.att.cloud.so.cloudapi.messages.IPNetworkType;
//import com.att.cloud.so.cloudapi.messages.SeverityType;
//import com.att.cloud.so.common.Errors;
import com.att.cloud.so.handlers.JAXPipelineHandler;
//import com.att.cloud.so.handlers.lb.LoadBalancerInterfaceHandler;
//import com.att.cloud.so.interfaces.ipv6.IPV6Subnet;
//import com.att.cloud.so.interfaces.ipv6.SubnetMgr;
//import com.att.cloud.so.interfaces.lb.LBServer;
//import com.att.cloud.so.interfaces.lb.LBServers;
//import com.att.cloud.so.interfaces.pipeline.PipelineEvents;
//import com.att.cloud.so.interfaces.pipeline.PipelineException;
//import com.att.cloud.so.interfaces.pipeline.PipelineMessage;
////import com.att.cloud.so.interfaces.pipeline.objects.PipelineNetwork;
//import com.att.cloud.so.interfaces.viprion.LBConfigState;
//import com.att.cloud.so.interfaces.viprion.LBStatePair;
//import com.att.cloud.so.interfaces.viprion.LoadBalancerConfiguration;
//import com.att.cloud.so.interfaces.viprion.MonitorConfig;
//import com.att.cloud.so.interfaces.viprion.ViprionGateway;
//import com.att.cloud.so.utils.LogEngine;
//import com.att.cloud.so.utils.SubnetIpTypes;
////import com.att.cloud.so.utils.Tools;
//
///* @author nd0563 [Naren Deshpande] */
//
public class AddLBIPv6ConfigurationHandler extends JAXPipelineHandler {
	
// ******** CURRENTLY THIS HANDLER IS NOT NEEDED AND NOT USED ********* 
	
	
//
//	private static Long DEFAULT_TIME_OUT = 1000L;
//	private String siteId, orgId;
//	private int vdcId, csId, vlanId;
//	private SubnetMgr subnetMgr;
//	private IPV6Subnet ipv6Subnet;
//	private List<LBConfigState> lbConfigStates = new ArrayList<LBConfigState>();
//	List<String> ipv6SelfIpList = new ArrayList<String>();
//	LoadBalancerInterfaceHandler lbIntf = new LoadBalancerInterfaceHandler();
//	
//	@Override
//	public void execute(PipelineMessage pmsg) throws PipelineException {
//		try {
//			LogEngine.debug( "Start AddLBIPv6ConfigurationHandler");
//			AttNetworkType attnetwork = (AttNetworkType)pmsg.getAttribute("attnetwork");
//			boolean useLb = false;
//
//			useLb = attnetwork.isUseLb();
//			if (!useLb)
//			{
//				LogEngine.debug("UseLb  is false. nothing to do in CreateLoadBalancerConfigurationHandler");
//				return;
//			}
//			initDba();
//			int cnId = pmsg.getAttributeAsInt("networkId");
//			LogEngine.debug("In AddLBIPv6ConfigurationHandler cnIddd : " + cnId);
//
//			lbIntf.openDb();
//			boolean dualStack = IPNetworkType.IP_V_4_V_6.value().equals(attnetwork.getNetworkType().value());
//			vlanId = getvLanFromAVI(cnId);
//			getVDCdetails(cnId); //Gets VDCID and CSID
//			siteId = pmsg.getAttributeAsStr("siteid");
//			orgId = pmsg.getAttributeAsStr("orgid");
//			LogEngine.debug("Before Pipline : new PipelineNetwork call , siteid, orgid" + siteId + orgId);
//			//PipelineNetwork pNetwork = new PipelineNetwork(cnId, orgId, siteId);
//			//LogEngine.debug("Before Pipline : new PipelineNetwork call : pNetwork " + pNetwork);
//
//			String service = attnetwork.getServiceType().name();
//			LogEngine.debug("AddLBIPv6ConfigurationHandler : Service" + service);
//
//			if (!dualStack)
//			{
//				LogEngine.debug("DualStack  is false. Nothing to do in Ipv6LoadBalancerConfigurationHandler");
//				return;
//			} else
//			{
//				if (!isINTERNET(service))
//				{
//					LogEngine.debug("Not an INET service. Nothing to do in Ipv6LoadBalancerConfigurationHandler");
//					return;
//				}
//			}
//			LogEngine.debug("Before Pipline : new PipelineNetwork call : ipv6Subnet " + ipv6Subnet);
//			//subnetMgr = new SubnetMgr();
//			ipv6Subnet = (IPV6Subnet)pmsg.getAttribute("ipv6Subnet");
//			LogEngine.debug("ipv6Subnet in ADDLBCONFIG  " + ipv6Subnet);
//
//			createIpv6LoadBalancerConfiguration();
//	
//		} catch (Exception e)
//		{
//			LogEngine.logException( e);
//			triggerRollback();	
//			pmsg.getResponse().addErrmsg(e.getMessage(), HttpServletResponse.SC_INTERNAL_SERVER_ERROR , SeverityType.FATAL,  e.getClass().getName());	
//			this.setAbortPipeline(true);
//		} finally
//		{
//			close();
//			lbIntf.closeDb();
//		}
//	}
//	
//	private void createIpv6LoadBalancerConfiguration()
//			throws Exception {
//				LogEngine.debug("IPV6_DEFAULT_GW:" + ipv6Subnet.getVip(SubnetIpTypes.IP_DEFAULT_GW).toString());
//				ipv6SelfIpList.add(ipv6Subnet.getVip(SubnetIpTypes.IP_LB_SELF1).toString() + "%" + vlanId);
//				ipv6SelfIpList.add(ipv6Subnet.getVip(SubnetIpTypes.IP_LB_SELF2).toString() + "%" + vlanId);
//				LogEngine.debug("IPV6_LB_SELF1:" + ipv6SelfIpList.get(0));
//				LogEngine.debug("IPV6_LB_SELF2:" + ipv6SelfIpList.get(1));
//				LogEngine.debug("IPV6_LB_FLOAT:"+ ipv6Subnet.getVip(SubnetIpTypes.IP_LB_FLOAT).toString());
//				LogEngine.debug("IPV6_LB_SNAT1:"+ ipv6Subnet.getVip(SubnetIpTypes.IP_LB_SNAT1).toString());
//				LogEngine.debug("IPV6_LB_SNAT2:"+ ipv6Subnet.getVip(SubnetIpTypes.IP_LB_SNAT2).toString());
//				LogEngine.debug("IPV6_LB_NETMASK AS ADRESS:"+ ipv6Subnet.getNetmask().asAddress().toString());
//				createLoadBalancerConfiguration(vlanId, new ArrayList<MonitorConfig>(), ipv6Subnet.getNetmask().asAddress().toString(),
//						LocalLBLBMethod.LB_METHOD_ROUND_ROBIN, ipv6SelfIpList, ipv6Subnet.getVip(SubnetIpTypes.IP_LB_FLOAT).toString()+ "%" + vlanId,
//						ipv6Subnet.getVip(SubnetIpTypes.IP_LB_SNAT1).toString(), ipv6Subnet.getVip(SubnetIpTypes.IP_LB_SNAT2).toString(), DEFAULT_TIME_OUT);
//	}
//	
//	private void createLoadBalancerConfiguration(Integer vlanId, ArrayList<MonitorConfig> monitorConfigList, String ipv6SubnetAddress, LocalLBLBMethod localLBMethod,
//			List<String> ipv6SelfIpList, String ipv6FloatingIP, String ipv6SnatIP1, String ipv6SnatIP2, Long timeout)
//			throws Exception {
//		LogEngine.debug("vlanId:" + vlanId + ", IpV6SubnetAddress:" + ipv6SubnetAddress + ", ipv6SelfIp1:" + ipv6SelfIpList.get(0)
//				+ ", ipv6SelfIp2:" + ipv6SelfIpList.get(1) + ", ipV6FloatingIp:" + ipv6FloatingIP + ", ipv6SnatIP1:" + ipv6SnatIP1 + ", ipv6SnatIP2:" + ipv6SnatIP2);
//		try {
//			int lbServerCount = 0;
//			for (LBServer lbServer : new LBServers().getServers(siteId)) {
//				LogEngine.debug("Creating IPV6-LoadBalancer Configuration on F5: "+ lbServer.getIp());
//				ViprionGateway viprionGateway = new ViprionGateway(lbServer.getConnectionString(), orgId, null);
//				LoadBalancerConfiguration loadBalancerConfiguration = new LoadBalancerConfiguration(vlanId, monitorConfigList, ipv6SubnetAddress,
//						localLBMethod, ipv6SelfIpList.get(lbServerCount++), ipv6FloatingIP, ipv6SnatIP1, ipv6SnatIP2, timeout);
//				loadBalancerConfiguration.setIpV4V6(true);
//				LBConfigState lbConfigState = viprionGateway.createLoadBalancerConfiguration(loadBalancerConfiguration);
//				lbConfigState.setServerId(lbServer.getServerid());
//				lbConfigStates.add(lbConfigState);
//				// Update table LBAppliance.
//				if (lbConfigState.isDeviceConfigSuccess()) {
//					int lbId = lbIntf.getLBID(lbServer.getServerid(), vdcId, csId);
//					lbIntf.updateLBApplianceforIpv6(ipv6SelfIpList, ipv6FloatingIP,ipv6SnatIP1, ipv6SnatIP2, lbServer.getServerid(),vdcId, csId);
//					lbIntf.addCustomerNetworkAppliance(ipv6Subnet.getCnId(), lbId, lbIntf.getLbVdeviceTyepId());
//					lbConfigState.setDbConfigSuccess(true);
//				} else
//					throw new Exception("Failed to create IPv6 Load Balancer Configuration");
//			}
//		} catch (Exception e) {
//			LogEngine.logException( e);
//			LogEngine.debug("Exception:" + e.getMessage());
//			throw e;
//		}
//	}
//
//
//
//	@Override
//	public void rollback() {
//		LogEngine.info("Rollingback [siteId:" + siteId + "]");
//		try {
//			initDba();
//			if (!lbConfigStates.isEmpty()) {
//				for (LBConfigState lbConfigState : lbConfigStates) {
//					for (LBServer lbServer : new LBServers().getServers(siteId)) {
//						if (lbServer.getServerid() == lbConfigState.getServerId()) {
//							try {
//								ViprionGateway viprionGateway = new ViprionGateway(lbServer.getConnectionString(), orgId,null);
//								lbConfigState.setRemoveVlan(true);
//								LogEngine.debug("RouteDomain: " + vlanId + ", VLAN: VLAN_" + vlanId);
//								lbConfigState.setRouteDomainState(new LBStatePair(String.valueOf(vlanId), true));
//								lbConfigState.setVlanState(new LBStatePair("VLAN_"+vlanId, true));
//								viprionGateway.rollbackLbConfig(lbConfigState);
//								//if (lbConfigState.isDbConfigSuccess())
//								lbIntf.deleteLbFromDB(String.valueOf(vdcId), csId, lbServer.getServerid());
//									//removeIpv6ConfigfromDB(vdcId, csId,	lbServer.getServerid());
//							} catch (Exception e) {
//								LogEngine.logException( e);
//								Errors.logAlarm("LB001","Unable to rollback Create Loadbalancer Config for VLAN : "+ vlanId + ", ServerId: " + lbServer.getIp());
//							}
//
//						}
//					}
//				}
//			} else{
//				//If lbConfigStates is empty, delete VLAN & RouteDomain
//
//				for (LBServer lbServer : new LBServers().getServers(siteId)) {
//					try {
//						ViprionGateway viprionGateway = new ViprionGateway(lbServer.getConnectionString(), orgId,null);
//						LBConfigState lbConfigState = new LBConfigState();
//						lbConfigState.setRemoveVlan(true);
//						LogEngine.debug("RouteDomain: " + vlanId + ", VLAN: VLAN_" + vlanId);
//						lbConfigState.setRouteDomainState(new LBStatePair(String.valueOf(vlanId), true));
//						lbConfigState.setVlanState(new LBStatePair("VLAN_"+vlanId, true));
//						viprionGateway.rollbackLbConfig(lbConfigState);
//						//if (lbConfigState.isDbConfigSuccess())
//						lbIntf.deleteLbFromDB(String.valueOf(vdcId), csId, lbServer.getServerid());
//					} catch (Exception e) {
//						LogEngine.logException( e);
//						Errors.logAlarm("LB001","Unable to rollback Create Loadbalancer Config for VLAN : "+ vlanId + ", ServerId: " + lbServer.getIp());
//					}
//				}
//			
//			
//				
//			}
//		} catch (Exception e) {
//			LogEngine.logException( e);
//			Errors.logAlarm("LB001","Unable to rollback Create Loadbalancer Config for VLAN : "+ vlanId);
//		} finally {
//			close();
//		}
//	}
//
//
//	@Override
//	public PipelineEvents handleEventType() {
//		return PipelineEvents.SERVICE_ATTNETWORK_PUT;
//	}
//	
//	private static final String GET_VLANID = "select lbavi from avi, customer_network cn where avi.aviid=cn.aviid and cn.cnid=?";
//	public int getvLanFromAVI(int cnId){
//		ResultSet rs = null;
//		try{
//			dba.prepareStatement(GET_VLANID);
//			dba.setInt(1, cnId);
//			LogEngine.debug("getvLanFromAVI() : before execute");
//
//			rs = dba.executeQuery();
//			LogEngine.debug("getvLanFromAVI() : After execute");
//
//			if(rs != null && rs.next()){
//				return rs.getInt("lbavi");
//			}
//		}catch(Exception e){
//			LogEngine.logException( e);
//			LogEngine.debug("Unable to get data from DB..getvLanFromAVI()" + e.getCause());
//			LogEngine.logException( e);
//		}finally{
//			dba.releasePStmt();
//			dba.closeRS(rs);
//		}
//		return -1;
//	}
//	
//	private static final String GET_VDCID = "select vdc.vdcid, vdc.csid, cn.service from customer_network cn, cs_inventory cs, vdc where vdc.csid=cs.csid and cs.ippid=cn.ippid and cn.cnid=?";
//	public void getVDCdetails(int cnId){
//		ResultSet rs = null;
//		try{
//			dba.prepareStatement(GET_VDCID);
//			dba.setInt(1, cnId);
//			LogEngine.debug("getVDCdetails() : Before execute");
//
//			rs = dba.executeQuery();
//			
//			LogEngine.debug("getVDCdetails() : After execute");
//
//			if(rs!=null && rs.next()){
//				vdcId = rs.getInt("vdcid");
//				csId = rs.getInt("csid");
//			}
//			LogEngine.debug("getVDCdetails() vdcId :" + vdcId );
//			LogEngine.debug("getVDCdetails() csId : " + csId);
//
//		}catch(Exception e){
//			LogEngine.logException( e);
//			LogEngine.debug("Unable to get VDCID from DB getVDCdetails() . . . " + e.getCause());
//			LogEngine.logException( e);
//		}finally{
//			dba.releasePStmt();
//			dba.closeRS(rs);
//		}
//	}
//	
//
}
