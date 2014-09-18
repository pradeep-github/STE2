package com.att.cloud.so.cloudapi.handlers.attnetwork.v2;

import java.sql.ResultSet;
import java.util.List;

import com.att.cloud.so.cloudapi.handlers.attnetwork.DeleteAlternateBackendAccess;
import com.att.cloud.so.handlers.lb.VipManager_v2;
import com.att.cloud.so.interfaces.pipeline.objects.PipelineNetwork;
import com.att.cloud.so.interfaces.vcd5.VCloudGateway5;
import com.att.cloud.so.utils.DBAccess;
import com.att.cloud.so.utils.LogEngine;
import com.att.cloud.so.utils.Tools;

public class DeleteAlternateBackendAccess_v2 extends DeleteAlternateBackendAccess{

	private static final long serialVersionUID = -3669332893627541153L;

	protected VCloudGateway5 vcg5 = null;
	
	protected static String GET_NOT_API_POLICIES = "select count(*) as count from lb_policies lbp where lbid in "+
			" (select applianceid from customer_network_appliance where VDEVICETYPEID = 3 and cnid =?) "+
			"and lbp.POLICY.visible = 'N' and lbp.POLICY.policyname <> 'API' and lbp.POLICY.virtualIp = ? ";
	protected int getCountOfNonAPIPolicies(int cnId, String vip) throws Exception{
		ResultSet rs = null;
		DBAccess dba = null;
		int policyCount = 0;
		try{
			dba = new DBAccess();
			dba.prepareStatement(GET_NOT_API_POLICIES);
			dba.setInt(1, cnId);
			dba.setString(2, vip);
			rs = dba.executeQuery();
			if(rs != null && rs.next()){
				policyCount = rs.getInt("COUNT");
				LogEngine.debug("Total no.of policies that are not API type: "+policyCount);
			}
		}catch(Exception e){
			LogEngine.debug("Unable to getCountOfNonAPIPolicies count  " + e.getMessage());	
			LogEngine.logException( e);
			throw e;
		}  finally {
			if(Tools.isNotEmpty(dba)) {
				dba.releasePStmt();
				dba.close(rs);
			}
		}
		return policyCount;
	}

	
	
	//
	protected void verifyAndFreeVips(int cnid, List<String> vips, PipelineNetwork pNetwork) throws Exception {
		
		try {
			LogEngine.debug("V2 handler , verifyAndFreeVips() : cnid:"+cnid);
			for(String vip : vips){
				String externalNetwork = pNetwork.getVcdNetName();
				String edgeGatewayHref = pNetwork.getEdgeGatewayHref();
				
				LogEngine.debug("Freeing VIP : "+vip);
				LogEngine.debug("cnid:"+cnid+"\tsiteId:"+siteId+"\texternalNetwork:"+externalNetwork+"\tedgeGatewayHref:"+edgeGatewayHref);
			
				vcg5 = new VCloudGateway5();
				vcg5.connect(siteId);
				
				if( getCountOfNonAPIPolicies(cnid, vip) > 0 ){
					LogEngine.debug("Policies other than API , exist on the same IP address of VIP:"+vip);
				}else{
					LogEngine.debug("Policies other than API: NONE, Freeing IP from VCD.");
					new VipManager_v2().freeVIPsInVCD(vShieldEnabled, externalNetwork , edgeGatewayHref, vip, vcg5);
				}
			}
		}catch(Exception e){
			LogEngine.debug("Exception at verifyAndFreeVips: "+e.getMessage() );
			LogEngine.logException(e);
			throw e;
		}finally {
			vcg5.disconnect();
		}
	}
}
