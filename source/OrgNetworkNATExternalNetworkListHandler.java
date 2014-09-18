package com.att.cloud.so.cloudapi.handlers.attnetwork;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import com.att.cloud.so.cloudapi.messages.SeverityType;
import com.att.cloud.so.handlers.JAXPipelineHandler;
import com.att.cloud.so.handlers.vdc.mq.NATExtListProcessor;
import com.att.cloud.so.interfaces.pipeline.PipelineEvents;
import com.att.cloud.so.interfaces.pipeline.PipelineException;
import com.att.cloud.so.interfaces.pipeline.PipelineMessage;
import com.att.cloud.so.interfaces.vcloud.VCloudGateway;
import com.att.cloud.so.utils.DBAccess;
import com.att.cloud.so.utils.LogEngine;
import com.att.cloud.so.utils.SOProperties;
import com.att.cloud.so.utils.Tools;
import com.vmware.vcloud.sdk.VCloudException;

/**
 * @author Shaik Apsar [sa709c@att.com]
 * @since 0.0.1
 *
 */
public class OrgNetworkNATExternalNetworkListHandler extends JAXPipelineHandler {

	/**
	 * 
	 */
	private static final long serialVersionUID = 3623784098161693838L;
	
	String networkGUID;
	List<NATExtListProcessor> natExtListProcessorList;
	Map<String, VCloudGateway> vCloudGatewayMap;
	
	private static final String GET_ROUTED_VDCS = "select o.orghref, T.* from org_to_site o, (select v.orgid, i.siteid, n.orgnetname, v.orgvdcid, i.ippid, n.orgnetguid, n.natextlist from vdc v, cs_inventory c, ipprefix i, network n where v.psid is not null and v.csid = c.csid and i.ippid = c.ippid and i.ippid = n.ippid and v.vshieldEnable = 'E' and (n.natextlist != 'S' or n.natextlist is null)) T where o.orgid = T.orgid and o.siteId = T.siteId";
	private static final String GET_ROUTED_VDCS_BY_NETWORKNAME = "select o.orghref, T.* from org_to_site o, (select v.orgid, n.orgnetguid, i.siteid, n.orgnetname, v.orgvdcid, i.ippid, n.natextlist from vdc v, cs_inventory c, ipprefix i, network n where v.csid = c.csid and v.vshieldEnable = 'E' and i.ippid = c.ippid and i.ippid = n.ippid and v.psid is not null and n.orgnetguid like ? and (n.natextlist is null or n.natextlist = 'F' )) T where o.orgid = T.orgid and o.siteId = T.siteId";
	private static final String UPDATE_ROUTED_VDC = "UPDATE network SET NATEXTLIST = ?, NAT_EXTLIST_UPDATE = SYSDATE WHERE ippid = ?";
	
	private static final String NATEXTLIST_UPDATE_SUCCESS = "S";
	private static final String NATEXTLIST_UPDATE_INPROGRESS = "P";
	private static final String NATEXTLIST_UPDATE_FAILED = "F";
	
	@Override
	public void execute(PipelineMessage pmsg) throws PipelineException {
		vCloudGatewayMap = new HashMap<String, VCloudGateway>();
		networkGUID = pmsg.getAttributeAsStr("networkId");
		natExtListProcessorList = new ArrayList<NATExtListProcessor>();
		ResultSet resultSet = null;
		String orgHref;
		String siteId = "";
		String orgNetworkName;
		String orgVDCID;
		String status;
		int ippid;
		String orgNetworkGUID;
		DBAccess dba = null;
		
		// USed to indicate what the names says
		boolean startedByCron = false;
		try {
			dba = new DBAccess();
			if("0".equals(networkGUID)) { // Initiated by CRON
				dba.prepareStatement(GET_ROUTED_VDCS);	
				startedByCron = true;
			} else { // Initiated by Rabbit
				waitforDBUpdate(0);
				Thread.sleep(60000); //Waiting for 1 more min extra
				dba.prepareStatement(GET_ROUTED_VDCS_BY_NETWORKNAME);
				dba.setString(1, "%"+networkGUID);
			}
			resultSet = dba.executeQuery();	
			
			/*
			 * When teh ORG NEtwork is created , the EVent is invoked so quickly by VCD that teh AddNetworkHandler as yet to 
			 * update the NETWORK Table.
			 * This causes this Handler to do nothing
			 * Instead will check if the query was empty
			 * Lets sleep a couple of seconds and 
			 * then will retry 
			 */
			if (resultSet == null && !startedByCron) {
				long nat_wait_foradd_completion = SOProperties.getProperty("NAT_WAIT_FORADD", 5000);
				LogEngine.eventHandlerLog("NAT_WAIT_FORADD:"+nat_wait_foradd_completion);
				Thread.sleep(nat_wait_foradd_completion);
				resultSet = dba.executeQuery();
			}
			
			while(resultSet !=null && resultSet.next()) {
				orgHref = resultSet.getString("orghref");
				siteId = resultSet.getString("siteid");
				orgVDCID = resultSet.getString("orgvdcid");
				orgNetworkName = resultSet.getString("orgnetname");
				ippid = resultSet.getInt("ippid");
				status = resultSet.getString("natextlist");
				orgNetworkGUID = resultSet.getString("orgnetguid");
				orgNetworkGUID = orgNetworkGUID.split("/network/")[1];
				
				if(!vCloudGatewayMap.containsKey(siteId)) {
					try {
						VCloudGateway vCloudGateway = new VCloudGateway();
						vCloudGateway.connect(siteId);
						vCloudGatewayMap.put(siteId, vCloudGateway);
					} catch (Exception exception) {
						LogEngine.eventHandlerLog("Unable to connect to the VCD in site : "+siteId);
						// Unable to connect to this sites VCD so move on to the once that can be connected
						continue;
					}
				}
				
				if(!Tools.isEmpty(orgHref)) {  // when orghref is not null
					LogEngine.eventHandlerLog( "Creating Processor for orgNetwork : "+orgNetworkName );
					NATExtListProcessor natExtListProcessor = new NATExtListProcessor(vCloudGatewayMap.get(siteId), orgNetworkName, orgHref, orgVDCID, ippid);
					if(NATEXTLIST_UPDATE_INPROGRESS.equalsIgnoreCase(status)) {
						continue;
					}
					if(vCloudGatewayMap.get(siteId).orgNetworkHasNATExtList(orgNetworkGUID)) {
						natExtListProcessor.setExtListUpdated( Boolean.TRUE );
					}
					
					if(startedByCron)natExtListProcessor.setNeedToResetOrgNetwork( Boolean.TRUE );
					natExtListProcessorList.add(natExtListProcessor);
					
					LogEngine.eventHandlerLog("Created Processor for orgNetwork : "+orgNetworkName);
				}
			}			
			
			// Join all the threads to the current thread
			//initDba();		// Not needed here - it's already been opened
			dba.releasePStmt();
			try {
				dba.prepareStatement(UPDATE_ROUTED_VDC);
				for(NATExtListProcessor natExtListProcessor : natExtListProcessorList) {
					dba.setString(1, NATEXTLIST_UPDATE_INPROGRESS);
					dba.setInt(2, natExtListProcessor.getIppid());
					dba.executeUpdate();
					LogEngine.eventHandlerLog("Starting Processor for orgNetwork : "+natExtListProcessor.getOrgNetworkName());
					natExtListProcessor.start();
				}
			} catch (Exception e) {
				throw e;
			} finally {
				dba.releasePStmt();
			}
			
			// Join all the threads to the current thread
			for(NATExtListProcessor natExtListProcessor : natExtListProcessorList) {
				LogEngine.eventHandlerLog("Joining Processor for orgNetwork : "+natExtListProcessor.getOrgNetworkName());
				natExtListProcessor.join();
			}
			
			//initDba();
			try {
				dba.prepareStatement(UPDATE_ROUTED_VDC);
				for(NATExtListProcessor natExtListProcessor : natExtListProcessorList) {
					dba.setString(1, natExtListProcessor.isExtListUpdated() ? NATEXTLIST_UPDATE_SUCCESS : NATEXTLIST_UPDATE_FAILED);
					dba.setInt(2, natExtListProcessor.getIppid());
					LogEngine.eventHandlerLog("Udpating Network : "+natExtListProcessor.getOrgNetworkName() + " with EXTIPLIST : "+natExtListProcessor.isExtListUpdated());
					dba.executeUpdate();
				}
			} catch (Exception e) {
				throw e;
			} finally {
				dba.releasePStmt();
			}
		} catch (Exception e) {
			LogEngine.logException(e);
			pmsg.getResponse().addErrmsg(e.getMessage(), HttpServletResponse.SC_INTERNAL_SERVER_ERROR , SeverityType.FATAL,  e.getClass().getName());
			this.setAbortPipeline(true);
		}finally{
			closeVCloudGateways();			 
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close(resultSet);
			}		
		}
	}
	
	@Override
	public PipelineEvents handleEventType() {
		return PipelineEvents.SERVICE_ATTNETWORK_NATEXTLIST;
	}

	private void waitforDBUpdate(int cycle) throws Exception{
		cycle++;
		if(cycle > 5){
			LogEngine.eventHandlerLog("Unable to find NetworkId in NetworkTable");
			return;
		}
		DBAccess dba = null;
		ResultSet rs = null;
		try {
			dba = new DBAccess();
			if(!Tools.isEmpty(networkGUID)){
				dba.prepareStatement("Select * from Network where ORGNETGUID like ?");
				dba.setString(1, "%"+networkGUID);
				rs = dba.executeQuery();
				if(rs != null && rs.next()){
					LogEngine.eventHandlerLog("Found network in DB");
					rs.close();
					return;
				}else{
					long nat_wait_foradd_completion = SOProperties.getProperty("NAT_WAIT_FORADD", 1000);
					LogEngine.eventHandlerLog("Network not found. Sleeping for DB Update");
					Thread.sleep(nat_wait_foradd_completion);
					LogEngine.eventHandlerLog("Awake from Sleep");
					waitforDBUpdate(cycle);
				}
			}else{
				LogEngine.eventHandlerLog("OrgNetwork not found in VCD");
				return;
			}
		}  finally {
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close(rs);
			}
		}
	}
	
	private void closeVCloudGateways() {
		for(String siteId : vCloudGatewayMap.keySet()) {
			try {
				vCloudGatewayMap.get(siteId).disconnect();
			} catch (Exception e) {
				LogEngine.logException(e);
			}
		}
	}
	
}
