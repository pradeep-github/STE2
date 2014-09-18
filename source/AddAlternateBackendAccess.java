package com.att.cloud.so.cloudapi.handlers.attnetwork;

import iControl.LocalLBLBMethod;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import com.att.cloud.so.cloudapi.messages.BackEndPolicyType;
import com.att.cloud.so.cloudapi.messages.SeverityType;
import com.att.cloud.so.cloudapi.utils.BackendPolicyType;
import com.att.cloud.so.cloudapi.utils.LBUtils;
import com.att.cloud.so.handlers.JAXPipelineHandler;
import com.att.cloud.so.handlers.lb.LoadBalancerInterfaceHandler;
import com.att.cloud.so.handlers.lb.VipManager;
import com.att.cloud.so.interfaces.AVIGateway;
import com.att.cloud.so.interfaces.lb.LBServer;
import com.att.cloud.so.interfaces.lb.LBServers;
import com.att.cloud.so.interfaces.nos.processors.VDeviceTypes;
import com.att.cloud.so.interfaces.pipeline.PipelineEvents;
import com.att.cloud.so.interfaces.pipeline.PipelineException;
import com.att.cloud.so.interfaces.pipeline.PipelineMessage;
import com.att.cloud.so.interfaces.pipeline.objects.PipelineNetwork;
import com.att.cloud.so.interfaces.vcloud.VCloudGateway;
import com.att.cloud.so.interfaces.viprion.BackEndPolicyConfig;
import com.att.cloud.so.interfaces.viprion.BridgeSnatPoolNetworkMgr;
import com.att.cloud.so.interfaces.viprion.LBConfigState;
import com.att.cloud.so.interfaces.viprion.LoadBalancerConfiguration;
import com.att.cloud.so.interfaces.viprion.MonitorConfig;
import com.att.cloud.so.interfaces.viprion.PolicyConfigState;
import com.att.cloud.so.interfaces.viprion.PolicyState;
import com.att.cloud.so.interfaces.viprion.ViprionGateway;
import com.att.cloud.so.udt.LB_Policy;
import com.att.cloud.so.utils.AVPNSubnet;
import com.att.cloud.so.utils.DBAccess;
import com.att.cloud.so.utils.IPRange;
import com.att.cloud.so.utils.LogEngine;
import com.att.cloud.so.utils.Subnet;
import com.att.cloud.so.utils.SubnetIpTypes;
import com.att.cloud.so.utils.Tools;

public class AddAlternateBackendAccess extends JAXPipelineHandler{
	private static final long serialVersionUID = 9122733154173936240L;
	protected BackEndPolicyType backendPolicyType = new BackEndPolicyType();
	private String siteId,service;
	protected String orgId;
	//	private String extNetworkName;
	//	private String subnetAddress;
	private int vLanId;
	protected int cnId;
	private int vdcId,csId;
	protected boolean vShieldEnabled = false;
	protected String virtualIp;
	protected boolean enableBackEndAccess;
	boolean forcedLb;
	private List<String> selfIpList = new ArrayList<String>();
	protected static final String IPv4_VS_NETMASK = "255.255.255.255";
	private List<LBConfigState> lbConfigStates = new ArrayList<LBConfigState>();
	private List<PolicyState> policyStatesAdded = new ArrayList<PolicyState>();
	private static final String DISABLE = "N";
	private static Long DEFAULT_TIME_OUT = 1000L;
	AVIGateway aviGateway = null;
	private String lbIdentifier;
	VCloudGateway vcg = null;
	LoadBalancerInterfaceHandler lbIntf = new LoadBalancerInterfaceHandler();
	BridgeSnatPoolNetworkMgr bridgeSnatPoolNetworkMgr = new BridgeSnatPoolNetworkMgr();

	//	Identify the appropriate network this refers to . If the VDC is associated with multiple network then the 1st one.
	//	Determine if the Network is ALREADY using the Load Balancer. Perhaps VSE is configure
	//	If its NOT using Back End access then 
	//	Initialize the Load Balancer Configuration
	//	If the Payload doesn't include the VIP then
	//	Define/generate a Vip to use, similar to what is done for the vSE VIP
	//	Depending on the BackEndType in the payload , identify the Servers associated with the VIP, and the Port as usual. Initially the ONLY backend type we will be receiving is API.
	//


	@Override
	public void execute(PipelineMessage pmsg) throws PipelineException {
		// Initializing DB connection
		try{
			// /cloudapi/location/[id-value]/attorg/[id-value]/attnetwork/[id-value]/addAlternateAccess
			siteId = pmsg.getAttributeAsStr("siteid");
			orgId = pmsg.getAttributeAsStr("orgid");
			cnId = pmsg.getAttributeAsInt("networkId");
			backendPolicyType = (BackEndPolicyType) pmsg.getAttribute("backendPolicyType");
			PipelineNetwork pNetwork = new PipelineNetwork(cnId, orgId, siteId);
			service = pNetwork.getAttnetwork().getServiceType().name();
			virtualIp = backendPolicyType.getVirtualIp();
			vShieldEnabled = pNetwork.getAttnetwork().isVshieldEnabled();
			
			if(isINTERNET(service)){
				LogEngine.debug("This is INTERNET Service. Nothing to do here.");
				return;
			}
			
			Subnet subnet = new AVPNSubnet(pNetwork.getAttnetwork().getSubnetAddress()+"/"+pNetwork.getAttnetwork().getSubnetPrefix());
			//This is now handled in Validate VIP method
			/*LBUtils lbUtils = new LBUtils();
			if(lbUtils.isAutoSnatLb(cnId)){
				subnet.lbWithAutoSnat();
			} else if(pNetwork.isForcedLb()) {
				subnet.forcedLB();
			} else if(pNetwork.getAttnetwork().isUseLb()) {
				subnet.useLB();
			} */
			
			// Get LB Policies VIPs 
			subnet.markAsAllocated((new VipManager()).getV4LBPolicyVips(cnId));
			
			validateVirtualIp(siteId, virtualIp, subnet, pNetwork);

			//			getNetworkDetails(cnId);
			//
			getVDCdetails(cnId);
			//			getLBDetails(cnId);
			//			getSubnets(cnId);

			//vShieldEnabled flag was already set in line no 96
			/*String fencedMode = getFencedMode(siteId, pNetwork);
			
			if (fencedMode.equalsIgnoreCase("NATROUTED"))
				vShieldEnabled = true;*/


			vLanId = getvLanFromAVI(cnId);
			// If vlanId is 0 then we will get new avi from the NO
			if(vLanId == 0){
				AVIGateway aviGateway = new AVIGateway();
				aviGateway.addLoadBalancer(cnId, true);
				vLanId=getvLanFromAVI(cnId);
			}
			//	if(isAVPN(service)){
			if(!pNetwork.isForcedLb() && !organizationHasOPLPoliciesInSite(orgId, siteId, cnId)){
				// Create Loadbalancer Configuration as there is no LB for this CNID

				//throw new Exception("LoadBalancer not configured for the network... Alternate access cannot be added..");
				LogEngine.debug("IP_DEFAULT_GW:"+ subnet.getIpFor(SubnetIpTypes.IP_DEFAULT_GW));
				selfIpList.add(subnet.getIpFor(SubnetIpTypes.IP_LB_SELF1)+"%"+vLanId);
				selfIpList.add(subnet.getIpFor(SubnetIpTypes.IP_LB_SELF2)+"%"+vLanId);
				LogEngine.debug("IP_LB_FLOAT:"+ subnet.getIpFor(SubnetIpTypes.IP_LB_FLOAT));
				LogEngine.debug("IP_LB_SELF1:"+ selfIpList.get(0));
				LogEngine.debug("IP_LB_SELF2:"+ selfIpList.get(1));
				String snatIPBE = null;
				boolean isNewSnatIpBe = false;
				BridgeSnatPoolNetworkMgr bridgeSnatPoolNetworkMgr = new BridgeSnatPoolNetworkMgr();
				if(bridgeSnatPoolNetworkMgr.needToCreateBridgeSnatPool(orgId, siteId)) {
					LogEngine.debug("Fetching new SnatIp from Subnet");
					isNewSnatIpBe = true;
					snatIPBE = bridgeSnatPoolNetworkMgr.getSnatIpfromSubnet(orgId, siteId);
				} else {
					snatIPBE = bridgeSnatPoolNetworkMgr.getSnatIpFor(orgId, siteId);
				}
				//				LogEngine.debug("snatIPBE: " + snatIPBE + ", isNewSnatIpBe: " + isNewSnatIpBe);
				//				LogEngine.debug("IP_LB_SNAT1:"+ subnet.getIpFor(SubnetIpTypes.IP_LB_SNAT1));
				//				LogEngine.debug("IP_LB_SNAT2:"+ subnet.getIpFor(SubnetIpTypes.IP_LB_SNAT2));
				createLoadBalancerConfiguration(cnId, vLanId, new ArrayList<MonitorConfig>(), subnet.netMask(), LocalLBLBMethod.LB_METHOD_ROUND_ROBIN, 
						selfIpList, subnet.getIpFor(SubnetIpTypes.IP_LB_FLOAT)+"%"+vLanId, snatIPBE, isNewSnatIpBe, DEFAULT_TIME_OUT);
				configureBackEndAccess(vLanId, siteId, subnet, pNetwork);

			}else{
				//Create only 'API' policy for this LB Configuration as BE LB is already configured.
				if(!organizationHasAPIPolicyInSite(orgId, siteId, cnId)){
					configureBackEndAccess(vLanId, siteId, subnet, pNetwork);
				} else {
					throw new Exception("API Policy already exist to LoadBalancer ");
				}
			}
			//}
		}catch(Exception e){
			LogEngine.logException( e);
			LogEngine.debug("Unable to create Alternate Backend Access ..." + e.getMessage());
			pmsg.getResponse().addErrmsg(e.getMessage(), HttpServletResponse.SC_INTERNAL_SERVER_ERROR , SeverityType.FATAL,  e.getClass().getName());
			this.setAbortPipeline(true);
		}
	}
	
	//Why are we fetching fence mode from ExternalNetwork to find whether the OrgVdcNetwork is NATRouted?
	//ExternalNetwork fence mode is always 'isolated'
	/*protected String getFencedMode(String siteId, PipelineNetwork pNetwork){
		String fencedMode = ""; 
		vcg = new VCloudGateway();
		try {
			vcg.connect(siteId);
			VMWExternalNetworkType vmwExternalNetworkType = vcg.getVMWExternalNetworkType(pNetwork.getVcdNetName());
			NetworkConfigurationType extNetConfiguration = vmwExternalNetworkType.getConfiguration();
			fencedMode = extNetConfiguration.getFenceMode();
		} catch (Exception e) {
			LogEngine.debug("Error getting VDC details.." + e.getMessage());
			LogEngine.logException( e);
			LogEngine.logException( e);
		} finally {
			vcg.disconnect();
		}
		return fencedMode;
	}*/

	protected void validateVirtualIp(String siteId, String vip, Subnet subnet, PipelineNetwork pNetwork) throws Exception{
		try {
			if(!Tools.isEmpty(vip)){
				String externalNetwork = pNetwork.getVcdNetName();
				String orgNetwork = pNetwork.getOrgNetName();
				try {
					vcg = new VCloudGateway();
					vcg.connect(siteId);
					List<String> extNetworkPoolIps = vcg.getUsableIpsforExtNetwork(externalNetwork);
					
					// When vShieldEnabledVDC is true
					String vSeIP = extNetworkPoolIps.get(0);
					if(pNetwork.getAttnetwork().isVshieldEnabled()){
						subnet.markAsAllocated(vSeIP); //This is the Ip allocated for VShield
					}
					
					//Mark IPs that are not existing in ExternalNetwork IPPool as Allocated
					subnet.markUsedIpsAsAllocated(extNetworkPoolIps);
					
					List<String> reservedIps = vShieldEnabled ? vcg.getReservedExternalIps(orgId, orgNetwork) : vcg.getIpAllocations(externalNetwork);
					
					subnet.markAsAllocated(subnet.defaulGateway());
					
					if(reservedIps != null && !reservedIps.isEmpty())
						subnet.markAsAllocated(reservedIps);
					
					if(reservedIps.contains(vip))
						throw new Exception("Invalid VirtualIp:" + vip+". It is alreday in use as a VIP or for a VApp/VM");

					if(!extNetworkPoolIps.contains(vip))
						throw new Exception("Invalid VirtualIp:" + vip+". IP is not within the external network static ip pool.");
					
				
					List<IPRange> ipranges = subnet.getAsUseableRanges();
					List<String> usableIps = new ArrayList<String>();
					for (IPRange ipRange : ipranges) {
						while(ipRange.hasNextIP()){
							usableIps.add(ipRange.getNextIP());
						}
					}
					if(!usableIps.contains(vip)){
						throw new Exception("Invalid VirtualIp:" + vip+". IP alredy in use.");
					}

					subnet.markAsUnallocated(vSeIP);
					subnet.markAsUnAllocated(reservedIps);
				} catch (Exception e) {
					LogEngine.debug("Error validating VirtualIp. " + e.getMessage());
					LogEngine.logException( e);
					throw e;
				} finally {
					vcg.disconnect();
				}
			} else{
				throw new Exception("VitualIp must be provided");
			}
		} catch (Exception e) {
			e.printStackTrace();
			throw e;
		}
	}
	
	//Use this when useLb=false, forcedLb=true and enableBackEndAccess=true i.e, when you have 1 floating IP, 2 self IPs, 1 backend snat IP
	private void createLoadBalancerConfiguration(int cnId, Integer vlanId, ArrayList<MonitorConfig> monitorConfigList, String netMask, 
			LocalLBLBMethod localLBMethod, List<String> selfIpList, String floatingIP, String snatIPBE, boolean isNewSnatIpBe, Long timeout) throws Exception{
		LogEngine.debug("vlanId:" + vlanId + ", netMask:"+ netMask + ", selfIp1:" + selfIpList.get(0) + ", selfIp2:" + selfIpList.get(1) + ", floatingIp:" + floatingIP + ", snatIPBE:" + snatIPBE);
		try {
			int lbServerCount = 0;
			lbIdentifier = Tools.getUniqueIdentifier();
			for(LBServer lbServer : new LBServers().getServers(siteId)) {
				LogEngine.debug("Creating LoadBalancer Configuration on F5: " + lbServer.getIp());
				ViprionGateway viprionGateway=new ViprionGateway(lbServer.getConnectionString(),orgId, String.valueOf(vdcId));
				LoadBalancerConfiguration loadBalancerConfiguration = new LoadBalancerConfiguration(vlanId, monitorConfigList, netMask, 
						localLBMethod, selfIpList.get(lbServerCount++), floatingIP, isNewSnatIpBe ? snatIPBE : null, timeout);
				LBConfigState lbConfigState = viprionGateway.createLoadBalancerConfiguration(loadBalancerConfiguration);
				lbConfigState.setServerId(lbServer.getServerid());
				lbConfigStates.add(lbConfigState);
				//Update table LBAppliance.
				if(lbConfigState.isDeviceConfigSuccess()){
					int lbId = lbIntf.createLBApplianceinDB(selfIpList,  floatingIP,  snatIPBE, lbServer.getServerid(), String.valueOf(vdcId), csId, lbIdentifier);
					lbIntf.addCustomerNetworkAppliance(cnId, lbId,  getVdeviceTyepId(VDeviceTypes.LOAD_BALANCER.getCode()));
					lbConfigState.setDbConfigSuccess(true);
				}
				else
					throw new Exception("Failed to create Load Balancer Configuration");
			}
		} catch (Exception e) {
			LogEngine.logException( e);
			LogEngine.debug("Exception:"+ e.getMessage());
			throw e;
		}
	}

	private static final String GET_VDEVICE_TYPE = "Select * from VDEVICE_TYPES vt where vt.VDEVICECODE = ?";
	public int getVdeviceTyepId(String vdeviceCode) throws Exception{
		ResultSet rs = null;
		DBAccess dba = null;
		try {
			dba = new DBAccess();
			dba.prepareStatement(GET_VDEVICE_TYPE);
			dba.setString(1, vdeviceCode);
			rs = dba.executeQuery();
			if (rs != null && rs.next()){
				return rs.getInt("VDEVICETYPEID");
			}else{
				throw new SQLException ("Unable VDEVICEID for: " + vdeviceCode);
			}
		} catch (Exception e) {
			LogEngine.logException( e);
			LogEngine.debug("Not able to get VDEVICETYPEID from database...");
			throw e;
		}  finally {
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close(rs);
			}
		}
	}


	private static final String GET_VDCID = "select vdc.vdcid, vdc.csid, cn.service from customer_network cn, cs_inventory cs, vdc where vdc.csid=cs.csid and cs.ippid=cn.ippid and cn.cnid=?";
	public void getVDCdetails(int cnId) throws Exception{
		ResultSet rs = null;
		DBAccess dba = null;
		try{
			dba = new DBAccess();
			dba.prepareStatement(GET_VDCID);
			dba.setInt(1, cnId);
			rs = dba.executeQuery();
			if(rs!=null && rs.next()){
				vdcId = rs.getInt("vdcid");
				csId = rs.getInt("csid");
			}
		}catch(Exception e){
			LogEngine.logException( e);
			LogEngine.debug("Unable to get VDCID from DB . . . " + e.getCause());
			throw e;
		} finally {
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close(rs);
			}
		}
	}

	private static final String GET_VLANID = "select lbavi from avi, customer_network cn where avi.aviid=cn.aviid and cn.cnid=? and cn.SITEID = ? and cn.SITEID = avi.SITEID";
	public int getvLanFromAVI(int cnId) throws Exception{
		ResultSet rs = null;
		DBAccess dba = null;
		try{
			dba = new DBAccess();
			dba.prepareStatement(GET_VLANID);
			dba.setInt(1, cnId);
			dba.setString(2, siteId);
			rs = dba.executeQuery();
			if(rs != null && rs.next()){
				return rs.getInt("lbavi");
			}
		} catch(Exception e){
			LogEngine.logException( e);
			LogEngine.debug("Unable to get data from DB.." + e.getCause());
			throw e;
		} finally {
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close(rs);
			}
		}
		return -1;
	}


	//Need CODE REVIEW for this method. 
	//Why are we fetching VIP from VIPManager instead of using the one validated from request or the one existing in database..??
	protected void configureBackEndAccess(int vlanId, String siteId, Subnet subnet, PipelineNetwork pNetwork) throws Exception {
		List<BackEndPolicyConfig> backEndPolicyConfigs = new ArrayList<BackEndPolicyConfig>();
		try{
			enableBackEndAccess = true; 
			String externalNetwork = pNetwork.getVcdNetName();
			String orgNetwork = pNetwork.getOrgNetName();
			
			if(Tools.isEmpty(virtualIp)){
				virtualIp = getVIPfromDB(cnId);
			}else{
				LogEngine.debug("User provided VIP:"+virtualIp);
				LogEngine.debug("Updating the VIP in VCD");
				try {
					vcg = new VCloudGateway();
					vcg.connect(siteId);
					new VipManager().updateVIPinVCD(vShieldEnabled, orgId, externalNetwork, orgNetwork, virtualIp, subnet, vcg);
				} catch (Exception e) {
					LogEngine.logException(e);
					throw e;
				} finally{
					vcg.disconnect();
				}
			}
			
			if(Tools.isEmpty(virtualIp)){
				//The flow should never come here
				virtualIp = new VipManager().getVirtualIpAddress(vShieldEnabled, siteId, orgId, externalNetwork, orgNetwork, subnet);
			}
			BackEndPolicyConfig backEndPolicyConfig = new BackEndPolicyConfig();
			backEndPolicyConfig.setVlan(vlanId);
			backEndPolicyConfig.setName(backendPolicyType.getBackEndType().toString());
			backEndPolicyConfig.setBackEndPolicyType(BackendPolicyType.VCC.value());
			backEndPolicyConfig.setSnatPoolName(BridgeSnatPoolNetworkMgr.getSnatPoolName(orgId));
			backEndPolicyConfig.setVirtualIp(virtualIp);
			backEndPolicyConfig.setNetMask(IPv4_VS_NETMASK);
			backEndPolicyConfig.setEnabled(true);
			backEndPolicyConfig.setOrgId(orgId);
			backEndPolicyConfigs.add(backEndPolicyConfig);

			addLoadBalancerBackEndPolicies(vlanId, backEndPolicyConfigs);
			//strictIsolation = false;
			updateBackEndAccess(enableBackEndAccess);
			//updateLBAppliance(false);
		} catch(Exception e){
			LogEngine.logException( e);
			LogEngine.debug("Error Configuring Backend Access " + e.getMessage());
			throw e;
		}
	}


	private static final String GET_BACKEND_VIP = "SELECT lbp.POLICY.virtualIP from LB_POLICIES lbp, CUSTOMER_NETWORK_APPLIANCE cna WHERE cna.CNID = ? and cna.VDEVICETYPEID = 3 " +
			" and cna.APPLIANCEID = lbp.LBID and lbp.POLICY.visible = 'N'";
	protected String getVIPfromDB(int cnId) throws Exception {
		ResultSet resultSet = null;
		DBAccess dba = null;
		try {
			dba = new DBAccess();
			dba.prepareStatement(GET_BACKEND_VIP);
			dba.setInt(1, cnId);
			resultSet = dba.executeQuery();
			if(resultSet != null && resultSet.next()){
				return resultSet.getString(1);
			}
		} catch (SQLException e) {
			LogEngine.logException(e);
			LogEngine.debug("Not able to get the virtual ip from the DB..");
			throw e;
		}  finally {
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close();
			}
		}
		return null;
	}

	protected void addLoadBalancerBackEndPolicies(int vlanId, List<BackEndPolicyConfig> backEndPolicyConfigs) throws Exception{
		Map<String, LB_Policy> backendPolicesMap = getBackEndPoliciesforSite();
		try{
			for(LBServer lbServer : new LBServers().getServers(siteId)) {
				LogEngine.debug("LoadBalancer: " + lbServer.getIp());
				PolicyState policyState = new PolicyState();
				policyState.setServerId(lbServer.getServerid());
				policyState.setVlanId(vlanId);

				for (BackEndPolicyConfig backEndPolicyConfig : backEndPolicyConfigs) {
					backEndPolicyConfig.setVlan(vlanId);
					backEndPolicyConfig.setMonitorTemplate(LBUtils.getBackendMonitorName(backEndPolicyConfig.getName()));
					backEndPolicyConfig.setPoolName(LBUtils.getBackendPoolName(backEndPolicyConfig.getName()));
					backEndPolicyConfig.setProtocol(backendPolicesMap.get(backEndPolicyConfig.getName()).getProtocol());
					backEndPolicyConfig.setPort(backendPolicesMap.get(backEndPolicyConfig.getName()).getPort());
				}

				ViprionGateway viprionGateway=new ViprionGateway(lbServer.getConnectionString(), orgId, null);
				//if the Load Balancer was pre-existing, it will modify the "Strict Isolation" for the  pre-existing  route domain
				viprionGateway.setStrictIsolation(vlanId, false);
				List<PolicyConfigState> policyConfigStates = viprionGateway.createVirtualServerforBackEndAccess(backEndPolicyConfigs);
				BridgeSnatPoolNetworkMgr snatPoolMgr = new BridgeSnatPoolNetworkMgr();
				String snatIp = snatPoolMgr.getSnatIpFor(orgId, siteId);
				if(Tools.isEmpty(snatIp)){
					LogEngine.debug("DB not having SNATIP for orgId " + orgId + " on site " + siteId);
					snatIp = snatPoolMgr.getSnatIpfromSubnet(orgId, siteId);
					LogEngine.debug("SnatIpBE: " + snatIp);
				}else{
					LogEngine.debug("SNATIPBE for orgId '" + orgId + "' on site " + siteId + ":" + snatIp);
				}
				viprionGateway.mapBESnatPoolToVS(snatIp, BridgeSnatPoolNetworkMgr.getSnatPoolName(orgId), backEndPolicyConfigs);
				for (PolicyConfigState policyConfigState : policyConfigStates) {
					if(!policyConfigState.isDeviceConfigSuccess()){
						policyState.setDeviceConfigSuccess(false);
						break;
					}
				}
				policyState.getPolicyStates().addAll(policyConfigStates);
				policyStatesAdded.add(policyState);
				if(policyState.isDeviceConfigSuccess()){
					for (PolicyConfigState policyConfigState : policyConfigStates) {
						for (BackEndPolicyConfig backEndPolicyConfig : backEndPolicyConfigs) {
							if(policyConfigState.getPolicyName().equals(backEndPolicyConfig.getName())){
								addBackEndPolicyInDB(lbServer.getServerid(), backendPolicesMap.get(backEndPolicyConfig.getName()), backEndPolicyConfigs);
								policyConfigState.setDbConfigSuccess(true);
							}
						}
					}
				}else{
					throw new Exception("Unable to add Policies to LoadBalancer " + policyState.isDeviceConfigSuccess());
				}
				policyState.setDbConfigSuccess(true);
			}
		}catch (Exception e){
			LogEngine.logException(e);
			LogEngine.debug("Unable to add Load Balancer Policies..");
			throw e;
		}
	}

	private void addBackEndPolicyInDB(int serverId, LB_Policy lbPolicy, List<BackEndPolicyConfig> backEndPolicyConfigs) throws Exception {
		String lba_query = "Select LBID from LB_APPLIANCE where vdcId = ?  and serverId = ? ";
		ResultSet rs = null;
		DBAccess dba = null;
		try{
			dba = new DBAccess();
			dba.prepareStatement(lba_query);
			dba.setInt(1, vdcId);
			dba.setInt(2, serverId);
			rs = dba.executeQuery();
			while(rs != null && rs.next()) {
				int lbId = rs.getInt(1);
				for (BackEndPolicyConfig backEndPolicyConfig : backEndPolicyConfigs) {
					if(backEndPolicyConfig.getName().equals(lbPolicy.getPolicyName())){
						lbPolicy.setVirtualIp(backEndPolicyConfig.getVirtualIp());
					}
				}
				lbPolicy.setVisible(DISABLE);
				if ( lbPolicy.insert(lbId) != 1) {
					throw new SQLException ("Unable to store Loadbalancer BackendPolicy for type: " + lbPolicy.getPolicyName());
				}
			}
		}catch(Exception e){
			LogEngine.logException(e);
			LogEngine.debug("Unable to add Backend Load Balancer Policy to DB");
			throw e;
		}  finally {
			if(Tools.isNotEmpty(dba)) {
				dba.close();
			}
		}
	}

	private  static final String UPDATE_VDC = "UPDATE VDC SET ENABLEBACKENDACCESS = ? where VDCID = ?";
	private static final String UPDATE_LB_APPLIANCE = "UPDATE LB_APPLIANCE SET FORCEDLB = ? , STRICTISOLATION = ? where VDCID = ?";
	protected void updateBackEndAccess(boolean enableBackEndAccess) throws Exception {
		DBAccess dba = null;
		try {
			dba = new DBAccess();
			dba.prepareStatement(UPDATE_VDC);
			dba.setString(1, "Y");
			dba.setInt(2, vdcId);
			if(dba.executeUpdate() < 1){
				throw new Exception("Unable to update ENABLEBACKENDACCESS for vdcId: " + vdcId);
			}
		} catch(Exception exception) { 
			LogEngine.logException( exception);
			LogEngine.debug("Unable to update Backend Access for Load Balancer Appliance");
			throw exception;
		}  finally {
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close();
			}
		}
	}


	@Deprecated
	protected void updateLBAppliance(boolean isStrictIsolation) throws Exception {
		DBAccess dba = null;
		try {
			dba = new DBAccess();
			dba.prepareStatement(UPDATE_LB_APPLIANCE);
			dba.setString(1, "Y");
			dba.setString(2, "Y");
			dba.setInt(3, vdcId);
			if(dba.executeUpdate() < 1){
				throw new Exception("Unable to update forceLb & StrictIsolation in LBAppliance for vdcId: " + vdcId);
			}
		} catch(Exception exception) {
			LogEngine.logException( exception);
			LogEngine.debug("Unable to update LB Appliance");
			throw exception;
		}  finally {
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close();
			}
		}
	}


	public Map<String, LB_Policy> getBackEndPoliciesforSite() throws Exception{
		Map<String, LB_Policy> backendPolicesMap = new HashMap<String, LB_Policy>();
		ResultSet rs = null;
		DBAccess dba = null;
		try {
			dba = new DBAccess();
			dba.prepareStatement("Select * from BACKEND_POLICY_TYPES where siteId = ?");
			dba.setString(1, siteId);
			rs = dba.executeQuery();
			while(rs != null && rs.next()){
				LB_Policy lbPolicy = (LB_Policy)rs.getObject("POLICY");
				backendPolicesMap.put(lbPolicy.getPolicyName(),  lbPolicy);
			}
			if(backendPolicesMap.keySet().isEmpty())
				throw new Exception("No default backend access policies available");
		} catch (Exception e) {
			LogEngine.logException( e);
			LogEngine.debug("Unable to get LoadBalancer Backend Policies for this site.");
			throw e;
		}  finally {
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close(rs);
			}
		}
		return backendPolicesMap;
	}

	private static final String GET_ORGANIZATION_OPL_POLICIES = "select lbp.* from lb_appliance lb, lb_policies lbp where lb.lbid in (select applianceid from customer_network_appliance where VDEVICETYPEID = 3 and cnid in (select cnid from customer_network where orgid = ? and siteid=? and cnid = ?)) and lb.lbid = lbp.lbid and lbp.POLICY.visible = 'N' and lbp.POLICY.policyName in (select bpt.POLICY.policyName from backend_policy_types bpt where bpt.beptype = 'OPL')";
	public boolean organizationHasOPLPoliciesInSite(String orgId, String siteId, int cnId) throws Exception {
		ResultSet resultSet = null;
		DBAccess dba = null;
		try {
			dba = new DBAccess();
			dba.prepareStatement(GET_ORGANIZATION_OPL_POLICIES);
			int p = 1;
			dba.setString(p++, orgId);
			dba.setString(p++, siteId);
			dba.setInt(p++, cnId);
			resultSet = dba.executeQuery();

			if(resultSet != null && resultSet.next()) {
				return true;
			}
		} catch (Exception e) {
			LogEngine.logException( e);
			throw e;
		}  finally {
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close(resultSet);
			}
		}
		return false;
	}	

	private static final String GET_ORGANIZATION_API_POLICY = "select lbp.* from lb_appliance lb, lb_policies lbp where lb.lbid in (select applianceid from customer_network_appliance where VDEVICETYPEID = 3 and cnid in (select cnid from customer_network where orgid = ? and siteid=? and cnid = ?)) and lb.lbid = lbp.lbid and lbp.POLICY.visible = 'N' and lbp.POLICY.policyName in (select bpt.POLICY.policyName from backend_policy_types bpt where bpt.POLICY.policyName='API')";
	public boolean organizationHasAPIPolicyInSite(String orgId, String siteId, int cnId) throws Exception {
		ResultSet resultSet = null;
		DBAccess dba = null;
		try {
			dba = new DBAccess();
			dba.prepareStatement(GET_ORGANIZATION_API_POLICY);
			int p = 1;
			dba.setString(p++, orgId);
			dba.setString(p++, siteId);
			dba.setInt(p++, cnId);
			resultSet = dba.executeQuery();

			if(resultSet != null && resultSet.next()) {
				return true;
			}
		} catch (Exception e) {
			LogEngine.logException( e);
			throw e;
		}  finally {
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close(resultSet);
			}
		}
		return false;
	}	

	@Override
	public PipelineEvents handleEventType() {
		return PipelineEvents.SERVICE_ATTNETWORK_ADDBEACCESS;
	}

}
