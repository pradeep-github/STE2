/**
 * 
 */
package com.att.cloud.so.cloudapi.handlers.attnetwork;

import java.sql.ResultSet;
import java.sql.SQLException;

import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.HttpMethod;

import com.att.cloud.so.cloudapi.messages.AttNetworkType;
import com.att.cloud.so.cloudapi.messages.CaasExtensionApiTypes;
import com.att.cloud.so.cloudapi.messages.LinkType;
import com.att.cloud.so.cloudapi.messages.ObjectFactory;
import com.att.cloud.so.cloudapi.messages.SeverityType;
import com.att.cloud.so.handlers.JAXPipelineHandler;
import com.att.cloud.so.interfaces.nos.processors.VDeviceTypes;
import com.att.cloud.so.interfaces.pipeline.PipelineEvents;
import com.att.cloud.so.interfaces.pipeline.PipelineException;
import com.att.cloud.so.interfaces.pipeline.PipelineMessage;
import com.att.cloud.so.interfaces.pipeline.objects.PipelineNetwork;
import com.att.cloud.so.interfaces.pipeline.objects.PipelineVdc;
import com.att.cloud.so.utils.DBAccess;
import com.att.cloud.so.utils.LogEngine;
import com.att.cloud.so.utils.Tools;
import com.att.cloud.so.utils.ipv6.NetworkTypes;

/**
 * @author ks114y [Kva Savitha]
 *
 */

/*	GET /cloudapi/location/[id-value]/attorg/[id-value]/attnetwork/[id-value]  ===> GetAttNetworkType   */
public class GetAttNetworkHandler extends JAXPipelineHandler {
	
	int cnId;
	String cId;
	String siteId, uri;
	public void execute(PipelineMessage pmsg) throws PipelineException {
		
		
		
		cnId = Integer.parseInt(pmsg.getAttributeAsStr("networkId"));
		cId = pmsg.getAttributeAsStr("orgId");
		siteId = pmsg.getAttributeAsStr("siteId");
		uri = pmsg.getUriAsStr();
		AttNetworkType attNetworkType=null;
		try {
			LogEngine.debug("PipelineMessage: " + pmsg.toString());
 
			attNetworkType=this.getNetworkType(cnId, cId, siteId);
			addNetworkLinks(uri, cnId, attNetworkType, cId, pmsg);
			attNetworkType.setHref(uri);
			attNetworkType.setId(String.valueOf(cnId));
			attNetworkType.setType(CaasExtensionApiTypes.APPLICATION_VND_ATT_SYNAPTIC_CLOUDAPI_ATT_NETWORK_XML.value());
			pmsg.addAttribute("attNetworkType", attNetworkType);
			
		} catch (Exception e) {
			if(e.getMessage().equalsIgnoreCase("Cannot find the mentioned Id in the Customer Network")){
				pmsg.getResponse().addErrmsg(e.getMessage(),HttpServletResponse.SC_NOT_FOUND, SeverityType.FATAL, e.getClass().getName());				
			}else {
				pmsg.getResponse().addErrmsg(e.getMessage(),HttpServletResponse.SC_INTERNAL_SERVER_ERROR, SeverityType.FATAL, e.getClass().getName());				
			}
			LogEngine.logException( e);
			this.setAbortPipeline(true);
			triggerRollback();
		} 
		
	}
	
	public AttNetworkType getNetworkType(int networkId, String orgId, String siteId) throws Exception {
		PipelineNetwork pipelineNetwork=new PipelineNetwork(networkId, orgId, siteId);
		AttNetworkType attNetworkType=pipelineNetwork.getAttnetwork();
		return attNetworkType;
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

	
	private static final String GET_LOADBALANCER_AND_OPLPOLICIES = "SELECT DISTINCT lba.lb_identifier " +
			"FROM customer_network_appliance cna, lb_appliance lba, VDEVICE_TYPES vdt, lb_policies lp " +
			"WHERE cna.cnid = ? and cna.applianceid = lba.lbid and cna.VDEVICETYPEID = vdt.VDEVICETYPEID " +
			"and vdt.VDEVICECODE = ? and lp.lbid = lba.lbid AND " +
			"(lba.FORCEDLB = 'N' OR lba.FORCEDLB = 'Y' and lp.policy.policyName in ('YUM', 'KMS'))";

	private static final String GET_LOADBALANCER = "SELECT DISTINCT lba.lb_identifier " +
			"FROM customer_network_appliance cna, lb_appliance lba, VDEVICE_TYPES vdt " +
			"WHERE cna.cnid = ? and cna.applianceid = lba.lbid and cna.VDEVICETYPEID = vdt.VDEVICETYPEID " +
			"and vdt.VDEVICECODE = ? and lba.FORCEDLB = 'N'";
	private String getLbIdentifier(int cnId) throws Exception{
    	ResultSet rs = null;
    	String lb_Identifier = "";
    	DBAccess dba = null;
  		try {
  			dba = new DBAccess();
  			if(organizationHasOPLPoliciesInSite(cId, siteId, cnId)){
  	  			dba.prepareStatement(GET_LOADBALANCER_AND_OPLPOLICIES);
  			}else{
  				dba.prepareStatement(GET_LOADBALANCER);
  			}
  			int pos = 1;
  			dba.setInt(pos++, cnId);
  			dba.setString(pos++, VDeviceTypes.LOAD_BALANCER.getCode());
  			rs = dba.executeQuery();
  			if (rs != null && rs.next()) {
  				lb_Identifier = rs.getString("lb_identifier");
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
  		return lb_Identifier;
	}
	
	private static final String GET_ORGANIZATION_OPL_POLICIES = "select lbp.* from lb_appliance lb, lb_policies lbp where lb.lbid in (select applianceid from customer_network_appliance where VDEVICETYPEID = 3 and cnid in (select cnid from customer_network where orgid = ? and siteid=? and cnid = ?)) and lb.lbid = lbp.lbid and lbp.POLICY.visible = 'N' and lbp.POLICY.policyName in (select bpt.POLICY.policyName from backend_policy_types bpt where bpt.beptype = 'OPL')";
	public boolean organizationHasOPLPoliciesInSite(String orgId, String siteId, int cnId) {
		/*
		 * we have to ignore the current network in consideration  
		 */
		DBAccess dba = null;
		ResultSet resultSet = null;
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
		}  finally {
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close(resultSet);
			}
		}
		return false;
	}


	private static final String GET_FIREWALL = "SELECT * from customer_network_appliance cna, fw_appliance fwa, VDEVICE_TYPES vdt where cna.cnid = ? and cna.applianceid = fwa.fwid and cna.VDEVICETYPEID = vdt.VDEVICETYPEID and vdt.VDEVICECODE = ?";
	private int getFireWall(int cnId) throws Exception{
		ResultSet rs = null;
		int fwId = 0;
		DBAccess dba = null;
		try{
			dba = new DBAccess();
			dba.prepareStatement(GET_FIREWALL);
  			int pos = 1;
  			dba.setInt(pos++, cnId);
  			dba.setString(pos++, VDeviceTypes.INSIDE_FIREWALL.getCode());
			rs=dba.executeQuery();
			if (rs != null && rs.next()) {
				fwId =  rs.getInt("fwid");
			}else{
  				LogEngine.debug("Firewall not found for Network Id: " + cnId);
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
		return fwId;
	}
	
//	private static final String GET_VDC = "SELECT orgvdcid from customer_network_appliance cna, lb_appliance lba, VDEVICE_TYPES vdt, vdc where cna.cnid = ? and cna.applianceid = lba.lbid " +
//										  " and vdc.vdcid = lba.vdcid and cna.VDEVICETYPEID = vdt.VDEVICETYPEID and vdt.VDEVICECODE = ?";
	private static final String GET_VDC = "select orgvdcid from customer_network cn, vdc_networks vn, vdc v where vn.vdcid = v.vdcid and vn.netid = cn.ippid and " +
										  " vn.nettype = cn.nettype and cn.cnid = ? ";
	
	private String getVDC(int cnId) throws Exception{
		ResultSet rs = null;
		String orgVdcId = "";
		DBAccess dba = null;
		try{
			dba = new DBAccess();
			dba.prepareStatement(GET_VDC);
  			int pos = 1;
  			dba.setInt(pos++, cnId);
  			//dba.setString(pos++, VDeviceTypes.LOAD_BALANCER.getCode());
			rs=dba.executeQuery();
			if (rs != null && rs.next()) {
				orgVdcId =  rs.getString("orgvdcid");
			}else{
  				LogEngine.debug("VDC not found for Network Id: " + cnId);
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

	public AttNetworkType addNetworkLinks(String uri, int networkId, AttNetworkType attNetworkType, String orgId, PipelineMessage pmsg) throws Exception {
		//  Adding LinkTypes for LoadBalancer, Firewall , VDC and associated network
		//  GET /cloudapi/location/[id-value]/attorg/[id-value]/attlb/[ID-VALUE]
		//  GET /cloudapi/location/[id-value]/attorg/{attorg_id}/attvdc/[ID-VALUE]
		//  GET /cloudapi/location/[id-value]/attorg/{attorg_id}/attfwp/[ID-VALUE]
		
		ObjectFactory factory = new ObjectFactory();
		
		String lbIdentifier = getLbIdentifier(networkId);
		if(!Tools.isEmpty(lbIdentifier) && attNetworkType.isUseLb()){
			String lbUri = uri.replaceAll("attnetwork.*$", "attlb/");
			LinkType lbLinkType = factory.createLinkType();
			lbLinkType.setAccept(CaasExtensionApiTypes.APPLICATION_VND_ATT_SYNAPTIC_CLOUDAPI_ATT_LOAD_BALANCER_XML.value());
			lbLinkType.setAction(HttpMethod.GET);
			lbLinkType.setHref(lbUri + lbIdentifier);
			lbLinkType.setId("" + lbIdentifier);
			lbLinkType.setMethod(HttpMethod.GET);
			lbLinkType.setType(CaasExtensionApiTypes.APPLICATION_VND_ATT_SYNAPTIC_CLOUDAPI_ATT_LOAD_BALANCER_XML.value());
			attNetworkType.getLinks().add(lbLinkType);
		}

		int fwId = getFireWall(networkId);
		if(fwId != 0){
			String fwUri = uri.replaceAll("attnetwork.*$", "attfwp/");
			LinkType fwLinkType = factory.createLinkType();
			fwLinkType.setAccept(CaasExtensionApiTypes.APPLICATION_VND_ATT_SYNAPTIC_CLOUDAPI_ATT_FIREWALL_POLICY_XML.value());
			fwLinkType.setAction(HttpMethod.GET);
			fwLinkType.setHref(fwUri + fwId);
			fwLinkType.setId(""+fwId);
			fwLinkType.setMethod(HttpMethod.GET);
			fwLinkType.setType(CaasExtensionApiTypes.APPLICATION_VND_ATT_SYNAPTIC_CLOUDAPI_ATT_FIREWALL_POLICY_XML.value());
			attNetworkType.getLinks().add(fwLinkType);	
		}

		String orgVdcId =  getVDC(networkId);
		int associatedNetwork = 0;
		PipelineVdc pipelineVdc = new PipelineVdc(orgVdcId, pmsg);
		LogEngine.debug( "**GETNETWORKS**" + pipelineVdc.getNetworks());
					
		for (PipelineNetwork pN : pipelineVdc.getNetworks())
		{
			if (pN.getCnId() != networkId)
				associatedNetwork = pN.getCnId();
		}
		
		if(!Tools.isEmpty(associatedNetwork)){
			if (associatedNetwork != 0){
				LinkType associatedNwlinkType = factory.createLinkType();
				associatedNwlinkType.setAction(HttpMethod.GET);
				associatedNwlinkType.setId(""+associatedNetwork);
				associatedNwlinkType.setHref(uri.split("attnetwork")[0] +"attnetwork/"+ associatedNetwork);
				associatedNwlinkType.setAccept(CaasExtensionApiTypes.APPLICATION_VND_ATT_SYNAPTIC_CLOUDAPI_ATT_NETWORK_XML.value());
				associatedNwlinkType.setMethod(HttpMethod.GET);
				associatedNwlinkType.setType(CaasExtensionApiTypes.APPLICATION_VND_ATT_SYNAPTIC_CLOUDAPI_ATT_NETWORK_XML.value());
				attNetworkType.getLinks().add(associatedNwlinkType);
			}
		}	
		if(!Tools.isEmpty(orgVdcId)){
			String vdcUri = uri.replaceAll("attnetwork.*$", "attvdc/");
			LinkType vdcLinkType = factory.createLinkType();
			vdcLinkType.setAccept(CaasExtensionApiTypes.APPLICATION_VND_ATT_SYNAPTIC_CLOUDAPI_ATT_VDC_XML.value());
			vdcLinkType.setAction(HttpMethod.GET);
			vdcLinkType.setHref(vdcUri + orgVdcId);
			vdcLinkType.setId(""+orgVdcId);
			vdcLinkType.setMethod(HttpMethod.GET);
			vdcLinkType.setType(CaasExtensionApiTypes.APPLICATION_VND_ATT_SYNAPTIC_CLOUDAPI_ATT_VDC_XML.value());
			attNetworkType.getLinks().add(vdcLinkType);	
		}
		
		return attNetworkType;
	}

	public PipelineEvents handleEventType() {
		return PipelineEvents.SERVICE_ATTNETWORK_GET;
	}
	
	public static void main(String[] args){
		PipelineMessage pmsg = new PipelineMessage();
		pmsg.addAttribute("networkId", "2721");
		pmsg.addAttribute("orgId", "999999");
		pmsg.addAttribute("siteId", "M400004");
		GetAttNetworkHandler netHandler = new GetAttNetworkHandler();
		try {
			netHandler.execute(pmsg);
		} catch (PipelineException e) {
			LogEngine.logException( e);
			LogEngine.logException( e);
		}
	}

	@Override
	public void rollback() {
		try {
			// Nothing to do here
		} catch (Exception e) {
			LogEngine.logException( e);
			LogEngine.logException( e);
		} 
	}
}