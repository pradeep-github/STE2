package com.att.cloud.so.cloudapi.handlers.attnetwork;

import iControl.LocalLBLBMethod;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.att.cloud.so.cloudapi.utils.LBUtils;
import com.att.cloud.so.handlers.JAXPipelineHandler;
import com.att.cloud.so.interfaces.lb.LBServer;
import com.att.cloud.so.interfaces.lb.LBServers;
import com.att.cloud.so.interfaces.nos.processors.VDeviceTypes;
import com.att.cloud.so.interfaces.pipeline.PipelineEvents;
import com.att.cloud.so.interfaces.pipeline.PipelineException;
import com.att.cloud.so.interfaces.pipeline.PipelineMessage;
import com.att.cloud.so.interfaces.pipeline.objects.PipelineNetwork;
import com.att.cloud.so.interfaces.vcloud.VCloudGateway;
import com.att.cloud.so.interfaces.viprion.BackEndPolicyConfig;
import com.att.cloud.so.interfaces.viprion.LoadBalancerConfiguration;
import com.att.cloud.so.interfaces.viprion.MonitorConfig;
import com.att.cloud.so.interfaces.viprion.ViprionGateway;
import com.att.cloud.so.udt.LB_Policy;
import com.att.cloud.so.utils.DBAccess;
import com.att.cloud.so.utils.LogEngine;
import com.att.cloud.so.utils.Tools;
import com.att.cloud.so.utils.ipv6.NetworkTypes;

public class DeleteAlternateBackendAccess extends JAXPipelineHandler{

	/**
	 * 
	 */
	private static final long serialVersionUID = 2893624438370307395L;
	private static Long DEFAULT_TIME_OUT = 1000L;
	protected String siteId, orgId, service;
	private int vlanId, cnId, vdcId;
	private List<Integer> lbIds = new ArrayList<Integer>(); 
	private List<String> selfIpList = new ArrayList<String>();
	//private List<String> snatIpList = new ArrayList<String>();
	private List<String> lbIps = new ArrayList<String>();
	private List<String> freeIps = new ArrayList<String>();
//	LoadBalancerInterfaceHandler lbIntf = new LoadBalancerInterfaceHandler();
	private VCloudGateway vcg = null;
	public LBUtils lbUtils = new LBUtils();
	protected boolean vShieldEnabled = false;

	//	Identify the appropriate network this refers to . If the VDC is associated with multiple network then the 1st one.
	//	Un-configure the Vip for the payload defined BackEndType . Initially the ONLY backend type we will be receiving is API.
	//	Determine if the Network is  using the Load Balancer for some other purpose, I.e. for VSE 
	//	If its NOT using Load Balancer for anything else then 
	//	Clean the Load Balancer Configuration


	//cloudapi/location/[id-value]/attorg/[id-value]/attnetwork/[id-value]/deleteAlternateAccess
	@Override
	public void execute(PipelineMessage pmsg) throws PipelineException {
//		lbIntf.openDb();
		try{
			siteId = pmsg.getAttributeAsStr("siteid");
			orgId = pmsg.getAttributeAsStr("orgid");
			cnId = pmsg.getAttributeAsInt("networkId");
//			lbIntf.setSiteId(siteId);
			//Validate request
			validateRequest();
			//TODO bs4939 Pnetwork customerId vs. Orgid
			PipelineNetwork pNetwork = new PipelineNetwork(cnId, orgId, siteId);
			vlanId = getvLanFromAVI(cnId);
			lbIds = getLbIds(cnId);
			vdcId = getVdcId(cnId);
			if( Tools.isNotEmpty(vdcId))
				vShieldEnabled = isVshieldEnabled(vdcId);
			
			if(vlanId <= 0 || lbIds.isEmpty())
				throw new Exception("Invalid request. The network doesn't have VCC enabled");
//			lbIntf.setLbVlan(vlanId);
//			lbIntf.setOrgId(orgId);
			service = pNetwork.getAttnetwork().getServiceType().name();
			//getSubnets(pmsg.getAttributeAsStr("networkId"));
			//Subnet subnet=null;
			if(!isINTERNET(service)){
//				subnet = new AVPNSubnet(pNetwork.getAttnetwork().getSubnetAddress()+"/"+pNetwork.getAttnetwork().getSubnetPrefix());
//				VipManager vipManager = new VipManager();

				//				since, we will have YUM and KMS policies attached to the LB so we will not delete LB Configuration.
				//				We will just delete the API policy in the LB 
				//if(lbUtils.organizationHasOPLPoliciesInSite(orgId, siteId, cnId)){
					deleteBEPolicy(cnId, vdcId, pNetwork); //No need to check if the organization has OPL policies since we are deleting only API policy.
				//}else{
				//	LogEngine.debug("Nothing to do here. AVPN/XCONN vdc will have OPL policies.");
					/*
					
					 * If this is the Last LB on a customerNetwork then in case this is ForcedLB then we need to delete only the API policy 
					 * else delete LB Configuration and free the vip in VCD.
					 
					if(isLastBEonCustomerNetworks(orgId, lbIds)){
						if(pNetwork.isForcedLb()){
							deleteBEPolicy(cnId);
						}else{
							deleteLoadBalancerConfiguration(true, (long)vlanId, new ArrayList<MonitorConfig>(), subnet.netMask(), 
									LocalLBLBMethod.LB_METHOD_ROUND_ROBIN, DEFAULT_TIME_OUT);
							deleteCustomerNetworkAppliance(cnId);
							// release ips to VCD
							vcg = new VCloudGateway();
							vcg.connect(siteId);
							for (String ip : lbIps) {
								String tokens[] = ip.split("%");
								freeIps.add(tokens[0]);
							}
							vipManager.freeLbIpsInVCD(true, orgId, extNetworkName, orgNetworkName, freeIps, vcg);
							if(getAviClusterDetailsCount(cnId) > 0){
								AVIGateway aviGateway = new AVIGateway();
								aviGateway.deleteLoadBalancer(cnId, true);
							}
						}
					}	
				*///}
			}
		else{
				throw new Exception("Only AVPN customers are allowed..");
			}

		}catch(Exception e){
			LogEngine.logException( e);
			LogEngine.debug(e.getMessage() + e.getCause());
			this.setAbortPipeline(true);
		} 
	}

	private static final String ISVSHIELDENABLED = "SELECT VSHIELDENABLE FROM VDC WHERE VDCID = ?";
	private boolean isVshieldEnabled(int vdcId2) throws Exception {
		ResultSet rs = null;
		DBAccess dba = null;
		boolean vShieldEnabled = false;
		try{
			dba = new DBAccess();
			dba.prepareStatement(ISVSHIELDENABLED);
			dba.setInt(1, vdcId2);
			rs = dba.executeQuery();
			if(rs!=null && rs.next()){
				if ( rs.getString("VSHIELDENABLE").equalsIgnoreCase("E"))
					vShieldEnabled = true;
			}
		}catch(Exception e){
			LogEngine.logException( e);
			LogEngine.debug("Unable to get VDCID from DB . . . " + e.getCause());
			throw e;
		} finally {
			if(Tools.isNotEmpty(dba)) {
				dba.close();
			}
		}
		return vShieldEnabled;
	}

	private static final String GET_VDCID = "select vdc.vdcid, vdc.csid, cn.service from customer_network cn, cs_inventory cs, vdc where vdc.csid=cs.csid and cs.ippid=cn.ippid and cn.cnid=?";
	public int getVdcId(int cnId) throws Exception{
		ResultSet rs = null;
		DBAccess dba = null;
		try{
			dba = new DBAccess();
			dba.prepareStatement(GET_VDCID);
			dba.setInt(1, cnId);
			rs = dba.executeQuery();
			if(rs!=null && rs.next()){
				vdcId = rs.getInt("vdcid");
			}
			return vdcId;
		}catch(Exception e){
			LogEngine.logException( e);
			LogEngine.debug("Unable to get VDCID from DB . . . " + e.getCause());
			throw e;
		} finally {
			if(Tools.isNotEmpty(dba)) {
				dba.close();
			}
		}
	}


	private static final String IDENTIFY_CUSTOMER_NETWORK = "SELECT * from CUSTOMER_NETWORK cn where cn.CNID = ? and cn.SITEID = ? and cn.ORGID = ?";
	private void validateRequest() throws Exception{
		ResultSet rs = null;
		DBAccess dba = null;
		try {
			dba = new DBAccess();
			dba.prepareStatement(IDENTIFY_CUSTOMER_NETWORK);
			dba.setInt(1, cnId);
			dba.setString(2, siteId);
			dba.setString(3, orgId);
			rs = dba.executeQuery();
			if(rs != null && rs.next()){
				if(rs.getInt("NETTYPE") != NetworkTypes.PUBLIC_IP_V4.getCode()){
					throw new Exception("Customer Network " + cnId + "should be Public IPV4");
				}
			}else{
				throw new Exception("CNID not found: " + cnId);
			}
		} catch (Exception e) {
			LogEngine.logException( e);
			throw e;
		}  finally {
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close(rs);
			}
		}
	}

	public static String GET_INISIIBLE_POLICIES = "select * from lb_policies lbp where lbid in (select applianceid from customer_network_appliance where VDEVICETYPEID = 3 and cnid =?) and lbp.POLICY.visible = 'N' and lbp.POLICY.policyname = ? ";
	public void deleteBEPolicy(int cnId, int vdcId, PipelineNetwork pNetwork) throws Exception{
		List<BackEndPolicyConfig> backEndPolicyConfigs = new ArrayList<BackEndPolicyConfig>();
		ResultSet rs = null;
		DBAccess dba = null;
		try{
			dba = new DBAccess();
			dba.prepareStatement(GET_INISIIBLE_POLICIES);
			dba.setInt(1, cnId);
			dba.setString(2, "API");
			rs = dba.executeQuery();
			while(rs != null && rs.next()){
				LB_Policy lbPolicy = (LB_Policy)rs.getObject("POLICY");
				BackEndPolicyConfig backEndPolicyConfig = new BackEndPolicyConfig();
				backEndPolicyConfig.setName(lbPolicy.getPolicyName());
				backEndPolicyConfig.setVlan(vlanId);
				backEndPolicyConfigs.add(backEndPolicyConfig);
				lbIps.add(lbPolicy.getVirtualIp());
			}
			verifyAndFreeVips(cnId, lbIps, pNetwork);
			for(LBServer lbServer : new LBServers().getServers(siteId)) {
				ViprionGateway viprionGateway=new ViprionGateway(lbServer.getConnectionString(),orgId, null);
				viprionGateway.deleteBackEndAccessLoadBalancerPolicies(backEndPolicyConfigs);
			}
			deleteLB_Policy_FromDB(vdcId);
		}catch(Exception e){
			LogEngine.debug("Unable to Delete Load Balancer Policy " + e.getMessage());	
			LogEngine.logException( e);
			throw e;
		}  finally {
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close(rs);
			}
		}
	}

	
	protected void verifyAndFreeVips(int cnid, List<String> vips, PipelineNetwork pNetwork) throws Exception {
		LogEngine.debug("V1 DeleteAlternateBackend Handler, nothing to do ");
	}

	public static String GET_LB_FOR_ORGANIZATION = "Select * from lb_appliance where lbid in (select applianceid from customer_network_appliance where VDEVICETYPEID = ? and cnid in (select cnid from customer_network where ORGID = ? and siteid=?))";
	public boolean isLastBEonCustomerNetworks(String orgId, List<Integer> lbIds) throws Exception{
		int vDeviceTypeId = getVdeviceTyepId(VDeviceTypes.LOAD_BALANCER.getCode());
		boolean isLastBE = false;
		ResultSet rs = null;
		DBAccess dba = null;
		try {
			dba = new DBAccess();
			dba.prepareStatement(GET_LB_FOR_ORGANIZATION);
			dba.setInt(1, vDeviceTypeId);
			dba.setString(2, orgId);
			dba.setString(3, siteId);
			rs = dba.executeQuery();
			while(rs != null && rs.next()){
				for(int lbid : lbIds){
					if(rs.getInt("LBID")!=lbid){
						if(!Tools.isEmpty(rs.getString("SNATBEIP"))){
							isLastBE = false;
							return isLastBE;
						}else{
							isLastBE = true;
						}
					}
				}
			}
			return isLastBE;
		}  finally {
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close(rs);
			}
		}
	}

	private static final String GET_VLANID = "select lbavi from avi, customer_network cn where avi.aviid=cn.aviid and cn.cnid=? and avi.siteId = cn.siteId";
	public int getvLanFromAVI(int cnId) throws Exception{
		ResultSet rs = null;
		DBAccess dba = null;
		try{
			dba = new DBAccess();
			dba.prepareStatement(GET_VLANID);
			dba.setInt(1, cnId);
			rs = dba.executeQuery();
			if(rs != null && rs.next()){
				return rs.getInt("lbavi");
			}else{
				throw new Exception("LBAVI not found");
			}
		}catch(Exception e){
			LogEngine.logException( e);
			LogEngine.debug("LBAVI not found");
			throw e;
		} finally {
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close(rs);
			}
		}
	}

	private static final String GET_AVI_CLUSTER_DETAILS_COUNT = "Select count(aviid) from avi_cluster ac where csavi = -1 and ac.aviid=(select aviid from customer_network where cnid = ?)";
	private int getAviClusterDetailsCount(int cnid) throws Exception{
		ResultSet rs = null;
		DBAccess dba = null;
		try{
			dba = new DBAccess();
			dba.prepareStatement(GET_AVI_CLUSTER_DETAILS_COUNT);
			dba.setInt(1, cnid);
			rs = dba.executeQuery();
			if (rs != null && rs.next() ) {
				return rs.getInt(1);
			}
		} catch (Exception e) {
			LogEngine.logException( e);
			throw e;
		}
		 finally {
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close(rs);
			}
		}
		return -1;
	}


	private static final String GET_LBID = "SELECT APPLIANCEID FROM CUSTOMER_NETWORK_APPLIANCE WHERE VDEVICETYPEID = ? and CNID = ?";
	private List<Integer> getLbIds(int cnId) throws Exception {
		List<Integer> lbidList = new ArrayList<Integer>();
		int vDeviceTypeId = getVdeviceTyepId(VDeviceTypes.LOAD_BALANCER.getCode());
		ResultSet rs = null;
		DBAccess dba = null;
		try {
			dba = new DBAccess();
			dba.prepareStatement(GET_LBID);
			dba.setInt(1, vDeviceTypeId);
			dba.setInt(2, cnId);
			rs = dba.executeQuery();
			while (rs != null && rs.next()) {
				lbidList.add(rs.getInt("APPLIANCEID"));
			}
		} catch (Exception e) {
			LogEngine.logException( e);
			throw e;
		}  finally {
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close(rs);
			}
		}
		return lbidList;
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
			throw e;
		}  finally {
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close(rs);
			}
		}
	}


	private void deleteLoadBalancerConfiguration(boolean isLastBE, Long vlanId, ArrayList<MonitorConfig> monitorConfigList, String subnetAddress, LocalLBLBMethod localLBMethod, Long timeout) throws Exception{
		ResultSet rs = null;
		DBAccess dba = null;
		try{
			String lbId = null;
			for (Integer lbid : lbIds) {
				lbId=String.valueOf(lbid);
			}
			dba = new DBAccess();
			//String query="SELECT * FROM LB_APPLIANCE, LB_POLICIES WHERE vdcid=? AND csid=? AND LB_POLICIES.lbid(+)=LB_APPLIANCE.lbid" ;
			String SELECT_LB_APPLIANCE = "SELECT * FROM LB_APPLIANCE WHERE LBID IN (" + lbId + ") " ;
			dba.prepareStatement(SELECT_LB_APPLIANCE);
			rs = dba.executeQuery();
			if( rs != null && rs.next()){
				int lbid = rs.getInt("lbid");
				String selfIP1=rs.getString("selfip1");
				String selfIP2=rs.getString("selfip2");
				String floatingIP=rs.getString("selffloatip");
				String snatIP1=rs.getString("snatip1");
				String snatIP2=rs.getString("snatip2");
				String snatIpBe = null;
				if(isLastBE) {
					snatIpBe = rs.getString("SNATBEIP");
				}
				selfIpList.add(selfIP1);
				selfIpList.add(selfIP2);

				lbIps.add(selfIpList.get(0));
				lbIps.add(selfIpList.get(1));
				lbIps.add(floatingIP);
				if (snatIP1 != null && snatIP2 != null) {
					lbIps.add(snatIP1);
					lbIps.add(snatIP2);
				}

				List<String> policyList = new ArrayList<String>();
				String SELECT_LB_POLICIES = "SELECT * FROM LB_POLICIES lbp WHERE lbp.lbid = ?";
				dba.releasePStmt();
				
				dba.prepareStatement(SELECT_LB_POLICIES);
				dba.setInt(1, lbid);
				ResultSet results = dba.executeQuery();
				while( results != null && results.next()){
					LB_Policy policy = (LB_Policy)results.getObject("POLICY");
					if(policy != null){
						if(!policyList.contains(policy.getPolicyName())){
							MonitorConfig monitorConfig = new MonitorConfig(null, policy.getPolicyName(), null, null, 0, 0, 0, null, null, null, null, null);
							monitorConfigList.add(monitorConfig);
							policyList.add(policy.getPolicyName());
							lbIps.add(policy.getVirtualIp());
						}
					}
				}
				dba.releasePStmt();
				
				for(LBServer lbServer : new LBServers().getServers(siteId)) {
					ViprionGateway viprionGateway=new ViprionGateway(lbServer.getConnectionString(),orgId, null);
					List<String> selfIpListFromDevice = viprionGateway.getSelfIpList();
					String selfIp = null;
					String floatIp = null;
					for (String slfIp : selfIpListFromDevice) {
						//If selfIp contains ":", it is ipv6 selfIp
						if(!slfIp.contains(":") && slfIp.endsWith("%"+vlanId)){
							LogEngine.debug("slfIp:" + slfIp);
							if(selfIpList.contains(slfIp)){
								LogEngine.debug("selfIp:" + slfIp);
								selfIp = slfIp;
							}else{
								LogEngine.debug("floatIp:" + slfIp);
								floatIp = slfIp;
							}
						}
					}
					LoadBalancerConfiguration loadBalancerConfiguration= new LoadBalancerConfiguration(vlanId, monitorConfigList, subnetAddress, 
							localLBMethod, selfIp, floatIp, snatIP1, snatIP2, timeout);
					if(isLastBE){
						loadBalancerConfiguration.setSnatIPBE(snatIpBe);
					}
					viprionGateway.deleteLoadBalancerConfiguration(loadBalancerConfiguration);
				}
				monitorConfigList.clear();
			}
			deleteLbFromDB();
		}catch(Exception e){
			LogEngine.logException( e);
			LogEngine.debug(e.getMessage());
			throw e;
		}
		 finally {
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close(rs);
			}
		}
	}
	private static final String UPDATE_LB_APPLIANCE = "UPDATE LB_APPLIANCE SET FORCEDLB = ? , STRICTISOLATION = ? where VDCID = ?";
	
	private void deleteLB_Policy_FromDB(int vdcId) throws Exception {
		DBAccess dba = null;
		try{
			String lbId = null;
			int flag = 0;
			StringBuffer sb = new StringBuffer("");
			for (Integer lbid : lbIds) {
				sb.append(lbid);
				flag++;
				if (flag != lbIds.size()) {
					sb.append(',');
				}
			}
			lbId = sb.toString();
			String delLB_API_Policy="DELETE lb_policies lbp WHERE lbp.LBID IN (" + lbId + ") and lbp.POLICY.policyname = ?";
			LogEngine.debug(delLB_API_Policy);
			
			dba = new DBAccess();
			dba.prepareStatement(delLB_API_Policy);
			dba.setString(1, "API");
			//Deleting API policies for Load Balancer.
			
			LogEngine.debug("No of rows deleted: " + dba.executeUpdate());
			
//			updateLBAppliance(vdcId);
			updateBackEndAccess(vdcId, false);
		}catch (Exception e){
			LogEngine.logException( e);
			LogEngine.debug(e.getMessage());
			throw e;
		}  finally {
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close();
			}
		}
	}

	private  static final String UPDATE_VDC = "UPDATE VDC SET ENABLEBACKENDACCESS = ? where VDCID = ?";
	private void updateBackEndAccess(int vdcId, boolean enableBackEndAccess) throws Exception {
		DBAccess dba = null;
		try {
			dba = new DBAccess();
			dba.prepareStatement(UPDATE_VDC);
			dba.setString(1, "N");
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
	private void updateLBAppliance( int cnId) throws Exception {
		DBAccess dba = null;
		try {
			dba = new DBAccess();
			dba.prepareStatement(UPDATE_LB_APPLIANCE);
			dba.setString(1, "N");
			dba.setString(2, "N");
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
	public void deleteLbFromDB() throws Exception {
		DBAccess dba = null;
		try{
			String lbId = null;
			int flag = 0;
			StringBuffer sb = new StringBuffer("");
			for (Integer lbid : lbIds) {
				sb.append(lbid);
				flag++;
				if (flag != lbIds.size()) {
					sb.append(',');
				}
			}
			lbId = sb.toString();
			String delLBPolicy="DELETE lb_policies lbp WHERE lbp.LBID IN (" + lbId + ")";
			LogEngine.debug(delLBPolicy);
			dba = new DBAccess();
			dba.prepareStatement(delLBPolicy);
			LogEngine.debug("No of rows deleted: " + dba.executeUpdate());
			dba.releasePStmt();
			String delLBAppliance="DELETE LB_APPLIANCE WHERE LBID IN (" + lbId + ") ";
			LogEngine.debug(delLBAppliance);
			dba.prepareStatement(delLBAppliance);
			if (dba.executeUpdate() < 1){
				throw new SQLException ("Unable to delete LBApplicance information");
			}
		}catch(Exception e){
			LogEngine.logException( e);
			throw e;
		}  finally {
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close();
			}
		}
	}

	private static final String DELETE_CUSTOMER_NETWORK_APPLIANCE = "DELETE customer_network_appliance cna WHERE VDEVICETYPEID = ? and cna.CNID = ?";
	private void deleteCustomerNetworkAppliance(int cnId) throws Exception {
		int vDeviceTypeId = getVdeviceTyepId(VDeviceTypes.LOAD_BALANCER.getCode());
		DBAccess dba = null;
		try{
			dba = new DBAccess();
			LogEngine.debug(DELETE_CUSTOMER_NETWORK_APPLIANCE + "cnid[" + cnId + "]");
			dba.prepareStatement(DELETE_CUSTOMER_NETWORK_APPLIANCE);
			dba.setInt(1, vDeviceTypeId);
			dba.setInt(2, cnId);
			if (dba.executeUpdate() < 1){
				throw new SQLException ("Unable to delete customer network appliance information for cnId : " + cnId);
			}
		}catch(Exception e){
			LogEngine.logException( e);
			throw e;
		}  finally {
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close();
			}
		}
	}


	/*private static final String GET_SUBNET="select iptype,prefix,subnetaddress from Ipprefix ip, customer_network cn where cn.ippid = ip.ippid and cn.cnid =?";
	public void getSubnets(String attNetworkId){
		ResultSet rs = null;
		try{
			dba.prepareStatement(GET_SUBNET);
			dba.setString(1, attNetworkId);
			rs = dba.executeQuery();
			if(rs != null && rs.next()){
				subnetAddress=rs.getString("SUBNETADDRESS");
				subnetPrefix=rs.getInt("PREFIX");
			}
		}catch(Exception e){
			LogEngine.logException( e);
			LogEngine.debug("Unable to get details from DB..." + e.getMessage());
		} finally{
			dba.releasePStmt();
			dba.closeRS(rs);
		}
	}*/

	public static void main(String[] args){
		PipelineMessage pmsg = new PipelineMessage();
		pmsg.addAttribute("siteid", "M400007");
		pmsg.addAttribute("orgid", "186334");
		pmsg.addAttribute("networkId", "514");
		DeleteAlternateBackendAccess delAltBE = new DeleteAlternateBackendAccess();
		
		try {
			delAltBE.execute(pmsg);
		} catch (PipelineException e) {
			LogEngine.logException( e);
		}
	}
	@Override
	public PipelineEvents handleEventType() {
		return PipelineEvents.SERVICE_ATTNETWORK_DELBEACCESS;
	}


}
