package com.att.cloud.so.cloudapi.handlers.attnetwork.v2;

import java.util.ArrayList;
import java.util.List;

import com.att.cloud.so.cloudapi.handlers.attnetwork.AddAlternateBackendAccess;
import com.att.cloud.so.cloudapi.utils.BackendPolicyType;
import com.att.cloud.so.handlers.lb.VipManager_v2;
import com.att.cloud.so.interfaces.pipeline.objects.PipelineNetwork;
import com.att.cloud.so.interfaces.vcd5.VCloudGateway5;
import com.att.cloud.so.interfaces.viprion.BackEndPolicyConfig;
import com.att.cloud.so.interfaces.viprion.BridgeSnatPoolNetworkMgr;
import com.att.cloud.so.utils.IPRange;
import com.att.cloud.so.utils.LogEngine;
import com.att.cloud.so.utils.Subnet;
import com.att.cloud.so.utils.SubnetIpTypes;
import com.att.cloud.so.utils.Tools;
import com.vmware.vcloud.api.rest.schema.NetworkConfigurationType;
import com.vmware.vcloud.api.rest.schema.extension.VMWExternalNetworkType;

public class AddAlternateBackendAccess_v2 extends AddAlternateBackendAccess{
	/**
	 * author : rb509d
	 */
	private static final long serialVersionUID = 5207967548652130891L;
	protected VCloudGateway5 vcg5 = null;
	private String vSEIPAddress = null;
	
	//Why are we fetching fence mode from ExternalNetwork to find whether the OrgVdcNetwork is NATRouted?
	//ExternalNetwork fence mode is always 'isolated'
	/*protected String getFencedMode(String siteId, PipelineNetwork pNetwork){
		LogEngine.debug("V2 Handler ,getFencedMode(siteId: "+siteId+")");
		LogEngine.debug("pNetwork.getVcdNetName():"+pNetwork.getVcdNetName());
		
		String fencedMode = ""; 
		vcg5 = new VCloudGateway5();
		try {
			vcg5.connect(siteId);
			VMWExternalNetworkType vmwExternalNetworkType = vcg5.getVMWExternalNetworkType(pNetwork.getVcdNetName());
			NetworkConfigurationType extNetConfiguration = vmwExternalNetworkType.getConfiguration();
			fencedMode = extNetConfiguration.getFenceMode();
			LogEngine.debug("fencedMode:"+fencedMode);
		} catch (Exception e) {
			LogEngine.debug("Error getting VDC details.." + e.getMessage());
			LogEngine.logException( e);
			LogEngine.logException( e);
		} finally {
			vcg5.disconnect();
		}
		return fencedMode;
	}*/
	
	//
	protected void configureBackEndAccess(int vlanId, String siteId, Subnet subnet, PipelineNetwork pNetwork) throws Exception {
		List<BackEndPolicyConfig> backEndPolicyConfigs = new ArrayList<BackEndPolicyConfig>();
		try{
			
			vcg5 = new VCloudGateway5();
			vcg5.connect(siteId);
			enableBackEndAccess = true; 
			String externalNetwork = pNetwork.getVcdNetName();
			String orgNetwork = pNetwork.getOrgNetName();
			String edgeGatewayHref = pNetwork.getEdgeGatewayHref();
			LogEngine.debug("V2 Handler:configureBackEndAccess( ):vlanId"+vlanId+"\tsiteId:"+siteId+"\tsubnet:"+subnet.getSubnetaddress()+"/"+subnet.getPrefix());
			LogEngine.debug("orgNetwork"+orgNetwork+"\texternalNetwork:"+externalNetwork+"\tedgeGatewayHref"+edgeGatewayHref);
			if(Tools.isEmpty(virtualIp)){
				LogEngine.debug("VIP is null, and hence getting from DB.");
				virtualIp = getVIPfromDB(cnId);
			}else{
				LogEngine.debug("User provided VIP:"+virtualIp);
				LogEngine.debug("Updating the VIP in VCD");
			/*
			 * v2 changes :
				0. If it is currently in use by any NAT rule throw an Exception
				validateVirtualIp in execute method
				1. if VSE VDC then remove IP from the Suballocation IP Pool
				2. make sure it is not there in IP Range of external Network	
			 */
				new VipManager_v2().updateVIPinVCD(vShieldEnabled, externalNetwork, edgeGatewayHref, virtualIp, vSEIPAddress, subnet, vcg5);
				
			}
			if(Tools.isEmpty(virtualIp)){
				/*Adding edgeGateway Href also to v2 method.*/
				virtualIp = new VipManager_v2().getVirtualIpAddress(vShieldEnabled, siteId, orgId, externalNetwork, orgNetwork, edgeGatewayHref, subnet);
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
		finally {
			vcg5.disconnect();
		}
	}

	
	//
	protected void validateVirtualIp(String siteId, String vip, Subnet subnet, PipelineNetwork pNetwork) throws Exception{
		try {
			
			LogEngine.debug("V2 handler : validateVirtualIp():siteId:"+siteId+"\tvip:"+vip+"\tsubnet:"+subnet.getSubnetaddress()+"/"+subnet.getPrefix());
			if(!Tools.isEmpty(vip)){
				String externalNetwork = pNetwork.getVcdNetName();
				//String orgNetwork = pNetwork.getOrgNetName();
				String edgeGatewayHref = pNetwork.getEdgeGatewayHref();
				String edgeGatewayId = Tools.getIdFromHref(edgeGatewayHref);
				vcg5 = new VCloudGateway5();
				try {
					vcg5.connect(siteId);
					List<String> extNetworkPoolIps = vcg5.getUsableIpsforExtNetwork(externalNetwork);
					
					//Mark IPs that are not existing in ExternalNetwork IPPool as Allocated
					subnet.markUsedIpsAsAllocated(extNetworkPoolIps);
					
					subnet.markAsAllocated(subnet.defaulGateway());
					
					List<String> reservedIps = new ArrayList<String>();
					if(vShieldEnabled){
						reservedIps = vcg5.getReservedEdgeGatewayExternalIps(edgeGatewayId, externalNetwork);
						//The first IP is always the EdgeVM IPAddress
						vSEIPAddress = reservedIps.get(0);
						if(reservedIps.contains(vip))
							throw new Exception("Invalid VirtualIp:" + vip+". IP address is already reserved in EdgeGatway external IPS (NAT IPs)");
					}else{
						reservedIps = vcg5.getIpAllocations(externalNetwork);
						if(reservedIps.contains(vip))
							throw new Exception("Invalid VirtualIp:" + vip+". IP address is allocated in the external network.");
					}
					
					if(!extNetworkPoolIps.contains(vip))
						throw new Exception("Invalid VirtualIp:" + vip+". IP is not within the range defined by the external network.");

					if(reservedIps != null && !reservedIps.isEmpty())
						subnet.markAsAllocated(reservedIps);
					
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
					
					subnet.markAsUnAllocated(reservedIps);

				} catch (Exception e) {
					LogEngine.debug("Invalid VirtualIp." + e.getMessage());
					LogEngine.logException( e);
					throw e;
				} finally {
					vcg5.disconnect();
				}
			} else{
				throw new Exception("VitualIp must be provided");
			}


		} catch (Exception e) {
			LogEngine.debug("LogException while validateVirtualIp:"+e.getMessage());
			e.printStackTrace();
			throw e;
		}
	}
}