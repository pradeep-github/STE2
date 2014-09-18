package com.att.cloud.so.cloudapi.handlers.attnetwork.v2;

import javax.servlet.http.HttpServletResponse;

import com.att.cloud.so.cloudapi.handlers.attnetwork.NetworkUpdateBlockingTaskHandler;
import com.att.cloud.so.cloudapi.messages.SeverityType;
import com.att.cloud.so.interfaces.pipeline.PipelineEvents;
import com.att.cloud.so.interfaces.pipeline.PipelineException;
import com.att.cloud.so.interfaces.pipeline.PipelineMessage;
import com.att.cloud.so.interfaces.pipeline.PipelineMessageDictionary;
import com.att.cloud.so.interfaces.vcd5.VCloudGateway5;
import com.att.cloud.so.utils.LogEngine;
import com.att.cloud.so.utils.Tools;
import com.vmware.vcloud.api.rest.schema.OrgVdcNetworkType;
import com.vmware.vcloud.sdk.Task;
import com.vmware.vcloud.sdk.VCloudException;
import com.vmware.vcloud.sdk.admin.User;

/**
 * @author Shaik Apsar [sa709c@att.com]
 * @since 0.0.1
 *
 */
public class NetworkUpdateBlockingTaskHandler_v2 extends NetworkUpdateBlockingTaskHandler {

	/**
	 * 
	 */
	private static final long serialVersionUID = -9051442231107446586L;
	private boolean vcg5Connected=false;
	private VCloudGateway5 vcg5=null;
	private String userName=null;
	
	@Override
	public void execute(PipelineMessage pmsg) throws PipelineException {
		try{
			if(openVCG(pmsg.getAttributeAsStr(PipelineMessageDictionary.SITEID))){
				networkId=pmsg.getAttributeAsStr("networkId");
				this.blockingTask=vcg5.getBlockingTaskById(pmsg.getAttributeAsStr("blockingTaskId"));
				String taskId=Tools.getIdFromHref(blockingTask.getTaskReference().getHref());
				Task task=vcg5.getTaskStatusById(taskId);				
				updateWithProcessingStatusBlockingTask();
				User user = vcg5.getUserByReferenceType(blockingTask.getUserReference());
				userName=user.getResource().getName();
				LogEngine.debug("requested User:"+userName);
				boolean isIgnoreOwner=canIgnoreOwner(userName);

				if(task.getResource().getParams() instanceof OrgVdcNetworkType){
					OrgVdcNetworkType params = (OrgVdcNetworkType)task.getResource().getParams();
					networkName = params.getName();
					LogEngine.eventHandlerLog("Network name from VCD Task Param OrgVdcNetworkType :"+networkName);
					if(Tools.isEmpty(networkName)){
						LogEngine.eventHandlerLog("The Network name is not populated from VCD response and considering this event is not Network rename");
						LogEngine.eventHandlerLog("NetworkUpdate is not renaming OrgVdcNetwork Name by User "+userName);
						resumeBlockingTask("OrgVdcNetwork update completed");
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
							if(networkType.equalsIgnoreCase("OrgVdcNetwork")){
								updateNetworkNameInDb(networkName,networkId);
							}else if(networkType.equalsIgnoreCase("ManagedOrgVdcNetwork")){
								updateManagedNetworkNameInDb(networkName,networkId);
							}								
						}
						return;
					}else{// We are here That means it's OrgVdcNetwork Change performed by end-user.
						LogEngine.eventHandlerLog("User: " + userName);
						try {
							vcg5.getOrgVdcNetworkById(networkId);
						} catch (Exception orgNotFoundEx) { 
							LogEngine.logException(orgNotFoundEx);
							resumeBlockingTask("OrgVdcNetwork does not exist in vCloud Director");
							isHandledBlockingTask=true;
							return;
						}						
						if(Tools.isEmpty(networkNameInDb)){ //OrgVdcNetwork not associated with SOBridge.
							LogEngine.eventHandlerLog("OrgVdcNetwork not associated with SO Bridge");
							resumeBlockingTask("OrgVdcNetwork is not associated with SO Bridge");
							isHandledBlockingTask=true;
						} else if(!Tools.isEmpty(networkName) && !networkName.equals(networkNameInDb)){ //OrgVdcNetwork name changes
							LogEngine.eventHandlerLog("Network rename is not allowed by organization user "+userName);
							abortBlockingTask("Network rename is not allowed by organization user");
							isHandledBlockingTask=true;
						}else{//Other than OrgVdcNetwork name changes
							LogEngine.eventHandlerLog("NetworkUpdate is not renaming OrgVdcNetwork Name "+userName);
							resumeBlockingTask("OrgVdcNetwork update completed");
							isHandledBlockingTask=true;
						}
					}
				}else{					
					LogEngine.eventHandlerLog("NetworkUpdate is not renaming OrgVdcNetwork Name by User "+userName);
					resumeBlockingTask("OrgVdcNetwork update completed");
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
	
	protected boolean openVCG(String siteId) throws VCloudException, Exception {
		if (!vcg5Connected || this.vcg5 == null) {
			try {
				vcg5 = new VCloudGateway5();
				LogEngine.info("VCG Created");
				vcg5.connect(siteId);
				LogEngine.info("VCG Connected");
				this.vcgConnected = true;
			} catch (Exception e) {
				LogEngine.logException(e);
			}
		}
		return this.vcgConnected;
	}	

	protected void closeVCG()  {
		try {
			this.vcg5Connected = false;
			this.vcg5.disconnect();
		} catch (Exception e) {
			// But nothing we can do
		}
	}
	
	@Override
	public PipelineEvents handleEventType() {
		return PipelineEvents.SERVICE_ATTNETWORK_PUT_BLOCKINGTASK_V2;
	}

}
