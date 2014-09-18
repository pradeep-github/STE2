package com.att.cloud.so.cloudapi.handlers.attnetwork;

import java.sql.ResultSet;
import java.sql.SQLException;

import javax.servlet.http.HttpServletResponse;

import com.att.cloud.so.cloudapi.messages.AttNetworkType;
import com.att.cloud.so.cloudapi.messages.IPNetworkType;
import com.att.cloud.so.handlers.JAXPipelineHandler;
import com.att.cloud.so.interfaces.ipv6.IPV6Subnet;
import com.att.cloud.so.interfaces.ipv6.SubnetMgr;
import com.att.cloud.so.interfaces.ipv6.SubnetNotFoundException;
import com.att.cloud.so.interfaces.nos.processors.VDeviceTypes;
import com.att.cloud.so.interfaces.pipeline.PipelineEvents;
import com.att.cloud.so.interfaces.pipeline.PipelineException;
import com.att.cloud.so.interfaces.pipeline.PipelineMessage;
import com.att.cloud.so.utils.DBAccess;
import com.att.cloud.so.utils.LogEngine;
import com.att.cloud.so.utils.Tools;
import com.att.cloud.so.utils.ipv6.IPV6Utils;
import com.att.cloud.so.utils.ipv6.NetworkTypes;

/*  @author nd0563 [Naren Deshpande] */
/*	PUT /cloudapi/location/[id-value]/attorg/[id-value]/attnetwork/[id-value]  ===> GetAttNetworkType   */

public class ModifyAttNetworkHandler extends JAXPipelineHandler {
	
	String networkId, orgid, siteId, uri, customerId;
	boolean convertToIpv6 = false;
	private SubnetMgr subnetMgr;
	private IPV6Subnet ipv6Subnet = null;
	int vdcid = 0, generatedKey;
	
	static final String ADD_VDC_NETWORK			    = "INSERT INTO VDC_NETWORKS(VDCID, NETID, AVIID, NETTYPE) VALUES ( ?, ?, ?, ? )" ;
	static final String ADD_CUSTOMER_NETWORK        = "Insert into customer_network (cnid, cid, netid,aviid,ippid, nettype, siteid, clusterid,service,orgid) values ( CUSTOMER_NETWORK_SEQ.NEXTVAL, ?, ?, ?, ?, ?, ?, ?, ?, ?) ";
	static final String GET_IPV6_NETWORK_ID		    = "select CUSTOMER_NETWORK_SEQ.currval from dual ";
	
	public void execute(PipelineMessage pmsg) throws PipelineException {
		DBAccess dba = null;
		try {
			LogEngine.debug("999991 IN SERVICE_ATTNETWORK_PUT: Execute");
			networkId = pmsg.getAttributeAsStr("networkId");
			customerId = pmsg.getAttributeAsStr("customer");
			
			orgid = pmsg.getAttributeAsStr("orgId");
			siteId = pmsg.getAttributeAsStr("siteId");
			uri = pmsg.getUriAsStr();
			
			String orgVdcId = getVDC(Integer.parseInt(networkId));
			vdcid = getVdcId(orgVdcId);
			AttNetworkType attNetworkType= (AttNetworkType)pmsg.getAttribute("attnetwork");
			validate(pmsg, networkId, orgid, attNetworkType, vdcid);
			String service = attNetworkType.getServiceType().name();
			
			if (convertToIpv6 == true)
			{
				
				subnetMgr = new SubnetMgr();
				ipv6Subnet = subnetMgr.allocateSubnet(orgid, siteId);
				pmsg.addAttribute("ipv6Subnet", ipv6Subnet);
				LogEngine.debug("ipv6Subnet in ModifyAttHandler  " + ipv6Subnet);
				LogEngine.debug(" Current ippid : getIPPid :: " + ipv6Subnet.getIPPid());

				if(ipv6Subnet == null) {
					throw new SubnetNotFoundException("Unable to find an ipv6 subnet for the customer : " + orgid + " siteId : " + siteId);
				}
				
				dba = new DBAccess();
				// Add the network to vdc_networks
				dba.prepareStatement(ADD_VDC_NETWORK);
				int index = 1;
				dba.setInt(index++, vdcid);
				dba.setInt(index++, ipv6Subnet.getIPPid());
				dba.setInt(index++, ipv6Subnet.getAviId());
				dba.setInt(index++, NetworkTypes.PUBLIC_IP_V4_V6.getCode());
				dba.executeUpdate();
				dba.releasePStmt();
				
				// Add the network to customer_networks
				dba.prepareStatement(ADD_CUSTOMER_NETWORK);
				index = 1;
				dba.setString(index++, customerId);
				dba.setInt(index++, ipv6Subnet.getIPPid());
				dba.setInt(index++, ipv6Subnet.getAviId());
				dba.setInt(index++, ipv6Subnet.getIPPid());
				dba.setInt(index++, NetworkTypes.PUBLIC_IP_V4_V6.getCode());
				dba.setString(index++, siteId);
				dba.setString(index++, null); /*cluster id */
				dba.setString(index++, service);
				dba.setString(index++, orgid);
				dba.executeUpdate();
				
				/*Get id of IPV6 network */
				dba.prepareStatement(GET_IPV6_NETWORK_ID);
				ResultSet rs = dba.executeQuery();
				if(rs!=null && rs.next()){
					generatedKey = (int) rs.getLong(1);
					LogEngine.debug(" generatedKey ::" + generatedKey);
				}
				
				dba.releasePStmt();
				
				updateDualStackInVdc(vdcid);
				
				add_FW_FB_for_IPV6();
								
				pmsg.getResponse().setResponseCode(HttpServletResponse.SC_OK);
			}
		} catch (SubnetNotFoundException e) {
			LogEngine.logException(e); 
			setAbortPipeline(true);
			triggerRollback();
		} catch (Exception e) {
			LogEngine.logException(e); 
			setAbortPipeline(true);
			triggerRollback();
		}  finally {
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close();
			}
		}
	}
	
	String GET_FW_FB_FOR_PRIMARY_NW = "SELECT * from customer_network_appliance where cnid = ?";
	String ADD_FW_FB_FOR_IPV6_NW    = " INSERT into customer_network_appliance (cnid,vdevicetypeid,applianceid) values (?,?,?) ";
	private void add_FW_FB_for_IPV6() throws Exception {
		ResultSet rs = null;
		DBAccess dba = null;
		try{
			dba = new DBAccess();
			dba.prepareStatement(GET_FW_FB_FOR_PRIMARY_NW);
			dba.setString(1, networkId);
			rs = dba.executeQuery();
			while (rs != null && rs.next()) {
				int vdevicetypeid = rs.getInt("VDEVICETYPEID");
				int applianceid = rs.getInt("APPLIANCEID");
				
				dba.prepareStatement(ADD_FW_FB_FOR_IPV6_NW);
				dba.setInt(1, generatedKey);
				dba.setInt(2, vdevicetypeid);
				dba.setInt(3, applianceid);
				dba.execute();
			}
		}catch(Exception e){
			LogEngine.logException(e);
			throw e;
		} finally {
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close(rs);
			}
		}
	}


	private void validate(PipelineMessage pmsg, String networkId, String orgId, AttNetworkType attNetworkType, int vdcid) throws Exception {
		//1. Verify if networkId is present
		if(networkId == null || networkId.trim().length()==0){
			pmsg.getResponse().setResponseCode(HttpServletResponse.SC_BAD_REQUEST);
			throw new Exception("networkId Id is empty or null:" + networkId);
		}
		
		//2. Request is to convert it to IPV6
		LogEngine.debug("attNetworkType.getNetworkType " + attNetworkType.getNetworkType());
		
		boolean dualStackInDB = isDualStack(vdcid);
		boolean dualStack = IPNetworkType.IP_V_4_V_6 == attNetworkType.getNetworkType();
		
		String customerSupportedIpNetwork = customerSupportedIpNetwork(orgId);
		//IPNetworkType ipNetworkType = attNetworkType.getNetworkType();
		String ipNetwork = null;
		if(true == dualStack) ipNetwork = "IP_V4_V6";
		LogEngine.debug(" STRING : ipNetwork " + ipNetwork);

		if(!ipNetwork.equalsIgnoreCase(customerSupportedIpNetwork)) {
			throw new Exception("Invalid request. Cannot switch ipNetworks.");
		}

		if (IPV6Utils.isIPV4(customerSupportedIpNetwork) && dualStack)
		{
			throw new Exception("Invalid request VDC cannot be modified as dual stack");
		}
		else if (IPV6Utils.isIPV4V6(customerSupportedIpNetwork) && dualStack)
		{
			if (dualStackInDB)
			{  // where request has dualStack true while db has dualStack 'Y'
				throw new Exception("No need to Convert IPV6, vDC already IPV6 dualstack");
			} else
			{ // where request has dualStack true while db has dualStack 'N'
				convertToIpv6 = true;
				LogEngine.debug(" In ModifyAttNetworkHandler : convertToIpv6 : " + convertToIpv6);
			}
		}
		else
		{
			throw new Exception("In Else : Either customerSupportedIpNetwork is V4 or dualStack is FALSE" + customerSupportedIpNetwork  + dualStack);
		}
		 
		String vshieldEnabledVdc = getVSHIELDENABLE(vdcid);
		
		if(("Y".equalsIgnoreCase(vshieldEnabledVdc))|| (("E".equalsIgnoreCase(vshieldEnabledVdc)))){
			throw new Exception(" vshieldEnabledVdc is TRUE ");
		}
	}

	public PipelineEvents handleEventType() {
		return PipelineEvents.SERVICE_ATTNETWORK_PUT;
	}
	
	public static void main(String[] args){
	}

	private static final String GET_IP_NETWORK = "SELECT org.ipnetwork FROM organization org WHERE org.orgid =?";
	private String customerSupportedIpNetwork(String orgId) throws Exception {
		ResultSet rs = null;
		String ipNetwork = null;
		DBAccess dba = null;
		try {
			dba = new DBAccess();
			LogEngine.debug(GET_IP_NETWORK + " " + orgId);
			dba.prepareStatement(GET_IP_NETWORK);
			dba.setString(1, orgId);
			rs = dba.executeQuery();
			if (rs != null && rs.next()) {
				ipNetwork = rs.getString("IPNETWORK");
			}

		} catch (Exception e) {
			LogEngine.logException(e);
			throw e;
		}  finally {
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close(rs);
			}
		}
		return ipNetwork;
	}
		
	//private static final String GET_VDC = "SELECT orgvdcid from customer_network_appliance cna, lb_appliance lba, VDEVICE_TYPES vdt, vdc where cna.cnid = ? and cna.applianceid = lba.lbid and vdc.vdcid = lba.vdcid and cna.VDEVICETYPEID = vdt.VDEVICETYPEID and vdt.VDEVICECODE = ?";
	private static final String GET_VDC = "select orgvdcid from customer_network cn, vdc_networks vn, vdc v where vn.vdcid = v.vdcid and vn.netid = cn.ippid and " +
			  " vn.nettype = cn.nettype and cn.cnid = ? ";

	private String getVDC(int networkId) throws Exception{
		ResultSet rs = null;
		String orgVdcId = "";
		DBAccess dba = null;
		try{
			dba = new DBAccess();
			dba.prepareStatement(GET_VDC);
  			int pos = 1;
  			dba.setInt(pos++, networkId);
			rs=dba.executeQuery();
			if (rs != null && rs.next()) {
				orgVdcId =  rs.getString("orgvdcid");
			}else{
  				LogEngine.debug("VDC not found for Network Id: " + networkId);
			}
		}catch(Exception e){
  			LogEngine.logException( e);
  			throw e;
		} finally {
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close(rs);
			}
		}
		return orgVdcId;
	}
		
	private static final String GET_VDCID = "SELECT VDCID FROM VDC WHERE ORGVDCID = ? ";
	private int getVdcId(String orgVdcId)throws Exception {
		int attVdcId = 0;
		ResultSet rs = null;
		DBAccess dba = null;
			try{
				dba = new DBAccess();
				dba.prepareStatement(GET_VDCID);
				dba.setString(1, orgVdcId);
				rs = dba.executeQuery();
				while (rs != null && rs.next()) {
					attVdcId = rs.getInt("VDCID");
				}
			}catch(Exception e){
				LogEngine.logException(e);
				throw e;
			}  finally {
				if(Tools.isNotEmpty(dba)) {
					dba.releasePStmt();
					dba.close(rs);
				}
			}
			LogEngine.debug("attVdcId in getVDCid method is" + attVdcId);
			return attVdcId;
	}
	
	//IS VDC dual stack ??
	public static String IS_DUAL = "select * from vdc where vdcid = ?";
	public boolean isDualStack(int vdcId) {
		ResultSet rs = null;
		DBAccess dba = null;
		try {
			dba = new DBAccess();
			dba.prepareStatement(IS_DUAL);
			int p = 1;
			dba.setInt(p++, vdcId);
			rs = dba.executeQuery();
			if (rs != null && rs.next()) {
				if (rs.getString("dualstack").equalsIgnoreCase("Y")) {
					return true;
				}
			}

		} catch (Exception e) {
			LogEngine.logException( e);
		}  finally {
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close(rs);
			}
		}
		return false;
	}

	//UPDATE VDC to dual stack
	private static final String UPDATE_DUALSTAK_IN_VDC = "UPDATE VDC SET DUALSTACK = 'Y' where vdcId = ?";
	private void updateDualStackInVdc(int vdcId) throws Exception {
		DBAccess dba = null; 
		try {
			dba = new DBAccess();
			dba.prepareStatement(UPDATE_DUALSTAK_IN_VDC);
			dba.setInt(1, vdcId);
			if(dba.executeUpdate() <= 0){
				LogEngine.debug("Unable to set DualStack = 'Y' for VdcId = " + vdcId);
			}
		} catch(Exception e) {
			LogEngine.logException(e);
			throw e;
		} finally {
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close();
			}
		}
	}
	
	private String VSHIELD_ENABLED = "Select VSHIELDENABLE from VDC vdc  where vdc.vdcid = ?";
	private String getVSHIELDENABLE(int vdcId) throws Exception{
		String vshieldenabled = "";
		
		LogEngine.debug("In get BackEnd Access");
		
		ResultSet results = null;
		DBAccess dba = null;
		try {
			dba = new DBAccess();
			dba.prepareStatement(VSHIELD_ENABLED);
			dba.setInt(1, vdcId);
			results = dba.executeQuery();
			while  (results.next()) {
				vshieldenabled = results.getString(1);
				LogEngine.debug("1) Value for Vshield Enabled " + vshieldenabled);
			}
		} catch (Exception e) {
			throw e;
		} finally {
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close(results);
			}
		}
		LogEngine.debug("2) Value for Vshield Enabled " + vshieldenabled);
		return vshieldenabled;
	}
	
	private static final String VDC_DELETE_V6_NETWORK = "DELETE from vdc_networks WHERE vdcid = ? AND netid = ?";
	private static final String CUSTOMER_DELETE_V6_NETWORK = "DELETE from customer_network WHERE cnid = ? ";
	private static final String CUSTOMER_DELETE_V6_NETWORK_APPLIANCE = "DELETE from customer_network_appliance WHERE cnid = ? ";
	private static final String DISABLE_DUALSTAK_IN_VDC = "UPDATE VDC SET DUALSTACK = 'N' where vdcId = ?";
	@Override
	public void rollback() {
		DBAccess dba = null;
		try {
			dba = new DBAccess();
			if(ipv6Subnet != null) {
				subnetMgr.deallocateSubnet(ipv6Subnet);
			
				dba.prepareStatement(VDC_DELETE_V6_NETWORK);
				int index = 1;
				dba.setInt(index++, vdcid);
				dba.setInt(index++, ipv6Subnet.getIPPid());
				dba.executeUpdate();
				dba.releasePStmt();
				
				dba.prepareStatement(CUSTOMER_DELETE_V6_NETWORK);
				index = 1;
				dba.setInt(index++, generatedKey);
				dba.executeUpdate();
				dba.releasePStmt();

				dba.prepareStatement(CUSTOMER_DELETE_V6_NETWORK_APPLIANCE);
				index = 1;
				dba.setInt(index++, generatedKey);
				dba.executeUpdate();
				dba.releasePStmt();
				
				dba.prepareStatement(DISABLE_DUALSTAK_IN_VDC);
				index = 1;
				dba.setInt(index++, vdcid);
				dba.executeUpdate();
			}
		} catch (Exception exception) {
			LogEngine.logException(exception); 
		}  finally {
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close();
			}
		}
	}
}