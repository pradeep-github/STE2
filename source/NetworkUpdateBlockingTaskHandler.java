package com.att.cloud.so.cloudapi.handlers.attnetwork;

import java.sql.ResultSet;
import java.sql.SQLException;

import javax.servlet.http.HttpServletResponse;

import com.att.cloud.so.cloudapi.messages.SeverityType;
import com.att.cloud.so.handlers.BlockingTaskJAXPipelineHandler;
import com.att.cloud.so.interfaces.pipeline.PipelineEvents;
import com.att.cloud.so.interfaces.pipeline.PipelineException;
import com.att.cloud.so.interfaces.pipeline.PipelineMessage;
import com.att.cloud.so.interfaces.pipeline.PipelineMessageDictionary;
import com.att.cloud.so.interfaces.vcloud.exceptions.OrgNetworkNotFoundException;
import com.att.cloud.so.utils.DBAccess;
import com.att.cloud.so.utils.LogEngine;
import com.att.cloud.so.utils.Tools;
import com.vmware.vcloud.api.rest.schema.OrgNetworkType;
import com.vmware.vcloud.sdk.Task;

/**
 * @author Shaik Apsar [sa709c@att.com]
 * @since 0.0.1
 *
 */
//PUT http://cloudso/SO/cloudapi/location/{location}/attorg/{orgname}/attnetwork/{networkId}/blockingTask/{}
public class NetworkUpdateBlockingTaskHandler extends BlockingTaskJAXPipelineHandler {

	/**
	 * 
	 */
	private static final long serialVersionUID = 6624375650843917558L;
	protected static final String[] OWNER_IGNORE_LIST = {"caasadmin","caasadm", "Administrator"};
	protected String networkName=null;
	protected String networkNameInDb=null;
	protected String networkType=null;
	protected String networkId=null;
	private String userName=null;

	@Override
	public void execute(PipelineMessage pmsg) throws PipelineException {
		try{ 
			if(openVCG(pmsg.getAttributeAsStr(PipelineMessageDictionary.SITEID))){
				networkId=pmsg.getAttributeAsStr("networkId");
				this.blockingTask=vcg.getBlockingTaskById(pmsg.getAttributeAsStr("blockingTaskId"));
				String taskId=Tools.getIdFromHref(blockingTask.getTaskReference().getHref());
				Task task=vcg.getTaskStatusById(taskId);				
				updateWithProcessingStatusBlockingTask();
				userName=blockingTask.getUserReference().getName();
				LogEngine.debug("requested User:"+userName);
				boolean isIgnoreOwner=canIgnoreOwner(userName);

				if(task.getResource().getParams() instanceof OrgNetworkType){
					OrgNetworkType params = (OrgNetworkType)task.getResource().getParams();
					networkName = params.getName();
					LogEngine.eventHandlerLog("Network name from VCD Task Param OrgNetworkType :"+networkName);
					if(Tools.isEmpty(networkName)){
						LogEngine.eventHandlerLog("The Network name is not populated from VCD response and considering this event is not Network rename");
						LogEngine.eventHandlerLog("NetworkUpdate is not renaming OrgNetwork Name by User "+userName);
						resumeBlockingTask("OrgNetwork update completed");
						isHandledBlockingTask=true;

						return;
					}
					if(Tools.isEmpty(networkNameInDb))
						networkNameInDb = getOrgNetworkName(networkId);
					if(Tools.isEmpty(networkNameInDb))
						networkNameInDb = getManagedOrgNetworkName(networkId);
					if(isIgnoreOwner){
						LogEngine.eventHandlerLog("Action performed by a System user " + userName );
						resumeBlockingTask("Action performed by a System user ");
						isHandledBlockingTask=true;
						if(!Tools.isEmpty(networkNameInDb) && !Tools.isEmpty(networkType)){
							if(networkType.equalsIgnoreCase("OrgNetwork")){
								updateNetworkNameInDb(networkName,networkId);
							}else if(networkType.equalsIgnoreCase("ManagedOrgNetwork")){
								updateManagedNetworkNameInDb(networkName,networkId);
							}								
						}
						return;
					}else{// We are here That means it's OrgNetwork Change performed by end-user.
						LogEngine.eventHandlerLog("User: " + userName);
						try {
							vcg.getNetworkByGUID(networkId);
						} catch (OrgNetworkNotFoundException orgNotFoundEx) { 
							LogEngine.logException(orgNotFoundEx);
							resumeBlockingTask("OrgNetwork does not exist in vCloud Director");
							isHandledBlockingTask=true;
							return;
						}						
						if(Tools.isEmpty(networkNameInDb)){ //OrgNetwork not associated with SOBridge.
							LogEngine.eventHandlerLog("OrgNetwork not associated with SO Bridge");
							resumeBlockingTask("OrgNetwork is not associated with SO Bridge");
							isHandledBlockingTask=true;
						} else if(!Tools.isEmpty(networkName) && !networkName.equals(networkNameInDb)){ //OrgNetwork name changes
							LogEngine.eventHandlerLog("Network rename is not allowed by organization user "+userName);
							abortBlockingTask("Network rename is not allowed by organization user");
							isHandledBlockingTask=true;
						}else{//Other than OrgNetwork name changes
							LogEngine.eventHandlerLog("NetworkUpdate is not renaming OrgNetwork Name by User "+userName);
							resumeBlockingTask("OrgNetwork update completed");
							isHandledBlockingTask=true;
						}
					}
				}else{					
					LogEngine.eventHandlerLog("NetworkUpdate is not renaming OrgNetwork Name by User "+userName);
					resumeBlockingTask("OrgNetwork update completed");
					isHandledBlockingTask=true;
					return;						
				}
			}

		}catch(Exception e){
			LogEngine.logException( e);
			try {
				if (!isHandledBlockingTask)
					abortFailedBlockingTask(e.getMessage()); // Aborting Blocking task when error is occurred while processing Request.
			} catch (Exception e1) {
				LogEngine.logException( e1);
			}		
			pmsg.getResponse().addErrmsg(e.getMessage(), HttpServletResponse.SC_INTERNAL_SERVER_ERROR , SeverityType.FATAL,  e.getClass().getName());
			this.setAbortPipeline(true);
		}finally{
			if(vcgConnected) closeVCG();
			
		}
	}	


	protected boolean canIgnoreOwner(String owner) {
		LogEngine.eventHandlerLog("User: " + owner);
		for(String ignoredOwner : OWNER_IGNORE_LIST) {
			if(ignoredOwner.equalsIgnoreCase(owner)) {
				return true;
			}
		}
		return false;
	}


	protected void updateNetworkNameInDb(String networkName, String networkId) {
		LogEngine.debug("Trying to update OrgNetworkName : "+ networkName+ " id"+networkId);
		DBAccess dba = null;
		try {
			dba = new DBAccess();
			dba.prepareStatement("UPDATE Network SET  orgnetname =? where ORGNETGUID like ?");
			dba.setString(1, networkName);
			dba.setString(2, "%"+networkId);
			if(dba.executeUpdate() < 1){
				LogEngine.debug("Unable to update OrgNetworkName Name : "+ networkName+ " id"+networkId);
			}else{
				LogEngine.debug("Updated OrgNetworkName : "+ networkName+ " id"+networkId);
			}
		} catch (Exception e) {
			LogEngine.logException(e);
		}	
		finally{
			if(dba!=null)
				dba.close();
		}

	}


	protected void updateManagedNetworkNameInDb(String networkName, String networkId) {
		LogEngine.debug("Trying to update ManagedOrgNetworkName : "+ networkName+ " id"+networkId);
		DBAccess dba = null;
		try {
			dba = new DBAccess();
			dba.prepareStatement("UPDATE managed_org_networks SET  orgnetname =? where ORGNETGUID like ?");
			dba.setString(1, networkName);
			dba.setString(2, "%"+networkId);
			if(dba.executeUpdate() < 1){
				LogEngine.debug("Unable to update ManagedOrgNetworkName Name : "+ networkName+ " id"+networkId);
			}else{
				LogEngine.debug("Updated ManagedOrgNetworkName Name : "+ networkName+ " id"+networkId);
			}
		} catch (Exception e) {
			LogEngine.logException(e);
		}  finally {
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close();
			}
		}

	}

	protected String getOrgNetworkName(String networkId) {
		LogEngine.debug("Trying to indentify OrgNetwork Blocking task entity from DB : "+ networkId);
		String orgNetworkName=null;
		ResultSet rs=null;
		DBAccess dba = null;
		try {
			dba = new DBAccess();
			dba.prepareStatement("Select * from network where ORGNETGUID like ?");
			dba.setString(1, "%"+networkId);
			rs = dba.executeQuery();
			if(!Tools.isEmpty(rs) && rs.next() ){
				orgNetworkName=rs.getString("ORGNETNAME");
				networkType="OrgNetwork";
			}
		} catch (Exception e) {
			LogEngine.logException(e);
		} finally {
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close(rs);
			}
		}
		return orgNetworkName;
	}

	protected String getManagedOrgNetworkName(String networkId) {
		LogEngine.debug("Trying to indentify ManagedorgNetName Blocking task entity from DB : "+ networkId);
		String managedOrgNetworkName=null;
		ResultSet rs=null;
		DBAccess dba = null;
		try {
			dba = new DBAccess();
			dba.prepareStatement("Select * from managed_org_networks where ORGNETGUID like ?");
			dba.setString(1, "%"+networkId);
			rs = dba.executeQuery();
			if(!Tools.isEmpty(rs) && rs.next() ){
				managedOrgNetworkName=rs.getString("ORGNETNAME");
				networkType="ManagedOrgNetwork";
			}
		} catch (Exception e) {
			LogEngine.logException(e);
		} finally {
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close(rs);
			}
		}
		return managedOrgNetworkName;
	}
	
	@Override
	public PipelineEvents handleEventType() {
		return PipelineEvents.SERVICE_ATTNETWORK_BLOCKINGTASK;
	}
}
