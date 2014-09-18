/**
 * 
 */
package com.att.cloud.so.cloudapi.handlers.attnetwork;

import java.sql.ResultSet;

import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.HttpMethod;

import com.att.cloud.so.cloudapi.messages.AttNetworkListType;
import com.att.cloud.so.cloudapi.messages.AttNetworkType;
import com.att.cloud.so.cloudapi.messages.CaasExtensionApiTypes;
import com.att.cloud.so.cloudapi.messages.LinkType;
import com.att.cloud.so.cloudapi.messages.ObjectFactory;
import com.att.cloud.so.cloudapi.messages.SeverityType;
import com.att.cloud.so.handlers.JAXPipelineHandler;
import com.att.cloud.so.interfaces.pipeline.PipelineEvents;
import com.att.cloud.so.interfaces.pipeline.PipelineException;
import com.att.cloud.so.interfaces.pipeline.PipelineMessage;
import com.att.cloud.so.interfaces.pipeline.objects.PipelineNetwork;
import com.att.cloud.so.interfaces.pipeline.objects.PipelineVdc;
import com.att.cloud.so.utils.DBAccess;
import com.att.cloud.so.utils.LogEngine;
import com.att.cloud.so.utils.Tools;

/**
 * @author sa709c[Shaik Apsar]
 *
 */
public class ListAttNetworkHandler extends JAXPipelineHandler{

	@Override
	public void execute(PipelineMessage pmsg) throws PipelineException {
		LogEngine.debug("Started ListAttNetworkHandler execute(..)");
		try {
			LogEngine.debug("PipelineMessage: " + pmsg.toString());
			int vdcid=-1;
			String orgVdcId = null;
			String recordType="link"; //If there is not query parameter, set default as "link"
			String orgId = pmsg.getAttributeAsStr("orgId");
			
			if(pmsg.hasAttribute("attvdc"))
				orgVdcId = pmsg.getAttributeAsStr("attvdc");
			LogEngine.debug("orgVdcId is" + orgVdcId);
			
			vdcid = getVdcId(orgVdcId);
			LogEngine.debug("vdcid is" + vdcid);

			if(pmsg.hasAttribute("recordType"))
		        recordType = pmsg.getAttributeAsStr("recordType");
	        
			String uri = pmsg.getUriAsStr();
			LogEngine.debug("vdcid: " + vdcid + ", RecordType: " + recordType + ", Uri: " + uri);
			if(vdcid <= 0){
				//Return all Networks in an Org 
				if(!Tools.isEmpty(recordType)){
					AttNetworkListType attnetworklist = getNetworkList(pmsg, orgId, recordType, uri);
					pmsg.addAttribute("attNetworkListType", attnetworklist);
				}else{
					throw new Exception("Invalid request. Queryparam recType is empty");
				}
			}else{
				//Return all Networks in an Org for a VDC
				if(!Tools.isEmpty(recordType)){
					AttNetworkListType attnetworklist = getNetworkList(pmsg, orgVdcId, vdcid, orgId, recordType, uri);
					pmsg.addAttribute("attNetworkListType", attnetworklist);
				}else{
					throw new Exception("Invalid request. Queryparam recType is empty");
				}
			}
		} catch (Exception e) {
			LogEngine.debug("Exception: "+e.toString());
			LogEngine.logException( e);
			pmsg.getResponse().addErrmsg(e.getMessage(), HttpServletResponse.SC_INTERNAL_SERVER_ERROR , SeverityType.FATAL,  e.getClass().getName());
		}	
	}
	
	private static final String GET_NETWORK_LIST = "SELECT * FROM customer_network cn WHERE cn.ORGID=?";
	private AttNetworkListType getNetworkList(PipelineMessage pmsg, String orgId, String recordType, String uri) throws Exception {
		ObjectFactory factory = new ObjectFactory();
		AttNetworkListType attnetworklist = factory.createAttNetworkListType();
		int pos=1;
		ResultSet rs = null;
		DBAccess dba = null;
		try {
			dba = new DBAccess();
			dba.prepareStatement(GET_NETWORK_LIST);
			dba.setString(pos++, orgId);
			rs = dba.executeQuery();
			while(rs != null && rs.next()){
				if("link".equalsIgnoreCase(recordType)){
					attnetworklist.getLinks().add(getNetworkLink(rs.getInt("CNID"), uri));
				}else if("object".equalsIgnoreCase(recordType)){
					AttNetworkType attNetworkType = getNetworkType(rs.getInt("CNID"), rs.getString("ORGID"),rs.getString("siteId"), uri, pmsg);
					if(attNetworkType != null)
						attnetworklist.getAttNetworks().add(attNetworkType);
				}
			}
		}
		catch (Exception e) {
			LogEngine.logException( e);
			throw e;
		}  finally {
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close(rs);
			}
		}
		return attnetworklist;
	}
	
	private static final String GET_NETWORK_LIST_FOR_VDC = "SELECT * FROM customer_network cn, vdc v, vdc_networks vn WHERE cn.ORGID = v.ORGID and vn.vdcid=v.vdcid and vn.aviid=cn.aviid and vn.netid = cn.ippid and cn.nettype=vn.nettype and v.vdcid= ? and v.ORGID = ? and vn.nettype in (1,2)";
	private AttNetworkListType getNetworkList(PipelineMessage pmsg, String orgVdcId, int vdcid, String orgId, String recordType, String uri) throws Exception {
		ObjectFactory factory = new ObjectFactory();
		AttNetworkListType attnetworklist = factory.createAttNetworkListType();
		int pos=1;
		ResultSet rs = null;
		DBAccess dba = null;
		try {
			dba = new DBAccess();
			dba.prepareStatement(GET_NETWORK_LIST_FOR_VDC);
			dba.setInt(pos++, vdcid);
			dba.setString(pos++, orgId);
			rs = dba.executeQuery();
			while(rs != null && rs.next()){
				if("link".equalsIgnoreCase(recordType)){
					attnetworklist.getLinks().add(getNetworkLink(rs.getInt("CNID"), uri));
				}else if("object".equalsIgnoreCase(recordType)){
					AttNetworkType attNetworkType = getNetworkType(rs.getInt("CNID"), orgId, rs.getString("siteId"), uri, pmsg);
					if(attNetworkType != null)
						attnetworklist.getAttNetworks().add(attNetworkType);
				}
			}
		}
		catch (Exception e) {
			LogEngine.logException( e);
			throw e;
		}  finally {
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close(rs);
			}
		}
		return attnetworklist;
	}
	
	//we are ignoring the networks that are out of sync
	public AttNetworkType getNetworkType(int cnId, String orgId, String siteId, String uri, PipelineMessage pmsg)  {
		AttNetworkType attNetworkType =  null;
		try {
			PipelineNetwork pipelineNetwork = new PipelineNetwork(cnId, orgId, siteId);
			attNetworkType = pipelineNetwork.getAttnetwork();
			attNetworkType.setHref(uri.split("attnetwork")[0] +"attnetwork/"+ cnId);
			attNetworkType.setId(String.valueOf(cnId));
			attNetworkType.setType(CaasExtensionApiTypes.APPLICATION_VND_ATT_SYNAPTIC_CLOUDAPI_ATT_NETWORK_XML.value());
			GetAttNetworkHandler getAttNetworkHandler = new GetAttNetworkHandler();
			return getAttNetworkHandler.addNetworkLinks(uri, cnId, attNetworkType, orgId, pmsg);
		} catch (Exception e) {
			LogEngine.info("Error while getting Network for Organization: " + orgId + ", CNID: " + cnId + ", SiteId: " + siteId);
			LogEngine.logException( e); 
		}
		return attNetworkType;
	}

	private LinkType getNetworkLink(int cnId , String uri) throws Exception{
		ObjectFactory factory = new ObjectFactory();
		LinkType linkType = factory.createLinkType();
		linkType.setAction(HttpMethod.GET);
		linkType.setId(""+cnId);
		linkType.setHref(uri.split("attnetwork")[0] +"attnetwork/"+ cnId);
		linkType.setAccept(CaasExtensionApiTypes.APPLICATION_VND_ATT_SYNAPTIC_CLOUDAPI_ATT_NETWORK_XML.value());
		linkType.setMethod(HttpMethod.GET);
		return linkType;
	}
	
	@Override
	public PipelineEvents handleEventType() {
		return PipelineEvents.SERVICE_ATTNETWORK_LIST;
	}
	
	@Override
	public void rollback() {
		try {
			// Nothing to do here
		} catch (Exception e) {
			LogEngine.logException( e);
		} 
	}
	
	 private static final String GET_VDCID = "SELECT VDCID FROM VDC WHERE ORGVDCID = ? ";
		
		private int getVdcId(String orgVdcId)throws Exception {
				// TODO Auto-generated method stub
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

}
