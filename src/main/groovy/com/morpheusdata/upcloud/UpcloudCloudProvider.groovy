package com.morpheusdata.upcloud

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.CloudProvider
import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.model.AccountCredential
import com.morpheusdata.core.util.ConnectionUtils
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.BackupProvider
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudFolder
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.Icon
import com.morpheusdata.model.Network
import com.morpheusdata.model.NetworkProxy
import com.morpheusdata.model.NetworkSubnetType
import com.morpheusdata.model.NetworkType
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.ProvisionType
import com.morpheusdata.model.StorageControllerType
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.request.ValidateCloudRequest
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.upcloud.sync.PublicTemplatesSync
import com.morpheusdata.upcloud.sync.UserImagesSync
import com.morpheusdata.upcloud.sync.VirtualMachinesSync
import com.morpheusdata.upcloud.util.UpcloudStatusUtility
import com.morpheusdata.upcloud.services.UpcloudApiService
import groovy.json.JsonOutput
import groovy.util.logging.Slf4j

@Slf4j
class UpcloudCloudProvider implements CloudProvider {
	public static final String CLOUD_PROVIDER_CODE = 'upcloud.cloud'

	protected MorpheusContext context
	protected Plugin plugin

	public UpcloudCloudProvider(Plugin plugin, MorpheusContext ctx) {
		super()
		this.@plugin = plugin
		this.@context = ctx
	}

	/**
	 * Grabs the description for the CloudProvider
	 * @return String
	 */
	@Override
	String getDescription() {
		return 'UpCloud'
	}

	/**
	 * Returns the Cloud logo for display when a user needs to view or add this cloud. SVGs are preferred.
	 * @since 0.13.0
	 * @return Icon representation of assets stored in the src/assets of the project.
	 */
	@Override
	Icon getIcon() {
		// TODO: change icon paths to correct filenames once added to your project
		return new Icon(path:'cloud.svg', darkPath:'cloud-dark.svg')
	}

	/**
	 * Returns the circular Cloud logo for display when a user needs to view or add this cloud. SVGs are preferred.
	 * @since 0.13.6
	 * @return Icon
	 */
	@Override
	Icon getCircularIcon() {
		// TODO: change icon paths to correct filenames once added to your project
		return new Icon(path:'cloud-circular.svg', darkPath:'cloud-circular-dark.svg')
	}

	/**
	 * Provides a Collection of OptionType inputs that define the required input fields for defining a cloud integration
	 * @return Collection of OptionType
	 */
	@Override
	Collection<OptionType> getOptionTypes() {
		Collection<OptionType> options = [
			new OptionType(code:'zoneType.upcloud.credential', inputType: OptionType.InputType.CREDENTIAL, name:'Credentials', category:'zoneType.upcloud',
				fieldName:'type', fieldCode:'gomorpheus.label.credentials', fieldLabel:'Credentials', fieldContext:'credential', fieldSet:'', fieldGroup:'Connection Options', required:true, enabled:true, editable:true, global:false,
				placeHolder:null, helpBlock:'', defaultValue:'local', custom:false, displayOrder:1, fieldClass:null, optionSource:'credentials', config: JsonOutput.toJson(credentialTypes:['username-password']).toString()),
			new OptionType(code:'zoneType.upcloud.username', inputType:OptionType.InputType.TEXT, name:'Username', category:'zoneType.upcloud',
				fieldName:'username', fieldCode: 'gomorpheus.optiontype.Username', fieldLabel:'Username', fieldContext:'config', fieldSet:'', fieldGroup:'Connection Options', required:true, enabled:true, editable:false, global:false,
				placeHolder:null, helpBlock:'', defaultValue:null, custom:false, displayOrder:2, fieldClass:null, localCredential:true),
			new OptionType(code:'zoneType.upcloud.password', inputType:OptionType.InputType.PASSWORD, name:'Password', category:'zoneType.upcloud',
				fieldName:'password', fieldCode: 'gomorpheus.optiontype.Password', fieldLabel:'Password', fieldContext:'config', fieldSet:'', fieldGroup:'Connection Options', required:true, enabled:true, editable:false, global:false,
				placeHolder:null, helpBlock:'', defaultValue:null, custom:false, displayOrder:3, fieldClass:null, localCredential:true),
			new OptionType(code:'zoneType.upcloud.zone', inputType:OptionType.InputType.SELECT, name:'Zone', category:'zoneType.upcloud',
				fieldName:'zone', fieldCode: 'gomorpheus.optiontype.Zone', fieldLabel:'Zone', fieldContext:'config', fieldSet:'', fieldGroup:'Connection Options', required:true, enabled:true, editable:false, global:false,
				placeHolder:null, helpBlock:'', defaultValue:null, custom:false, displayOrder:4, fieldClass:null, optionSourceType: null, optionSource: 'upcloud.upcloudCloudDataset', dependsOn: 'config.username, credential.type, credential.username, credential.password')
		]

		return options
	}

	/**
	 * Grabs available provisioning providers related to the target Cloud Plugin. Some clouds have multiple provisioning
	 * providers or some clouds allow for service based providers on top like (Docker or Kubernetes).
	 * @return Collection of ProvisionProvider
	 */
	@Override
	Collection<ProvisionProvider> getAvailableProvisionProviders() {
	    return this.@plugin.getProvidersByType(ProvisionProvider) as Collection<ProvisionProvider>
	}

	/**
	 * Grabs available backup providers related to the target Cloud Plugin.
	 * @return Collection of BackupProvider
	 */
	@Override
	Collection<BackupProvider> getAvailableBackupProviders() {
		Collection<BackupProvider> providers = []
		return providers
	}

	/**
	 * Provides a Collection of {@link NetworkType} related to this CloudProvider
	 * @return Collection of NetworkType
	 */
	@Override
	Collection<NetworkType> getNetworkTypes() {
		Collection<NetworkType> networks = [
			new NetworkType(code:'dockerBridge', name:'Docker Bridge', description:'', overlay:false, creatable:false, nameEditable:false,
				cidrEditable:false,  cidrRequired:false, dhcpServerEditable:false, dnsEditable:false, gatewayEditable:false, vlanIdEditable:false, canAssignPool:false,
				deletable:false, hasNetworkServer:false, hasCidr:true),
			new NetworkType(code:'overlay', name:'Overlay', description:'', overlay:true, creatable:false, nameEditable:true, cidrEditable:true, cidrRequired:false,
				dhcpServerEditable:true, dnsEditable:true, gatewayEditable:true, vlanIdEditable:true, canAssignPool:true, deletable:true,
				hasNetworkServer:false, hasCidr:true),
			new NetworkType(code:'host', name:'Host Network', description:'', overlay:false, creatable:true, nameEditable:true, cidrEditable:true, cidrRequired:false,
				dhcpServerEditable:true, dnsEditable:true, gatewayEditable:true, vlanIdEditable:true, canAssignPool:true, deletable:true,
				hasNetworkServer:false, hasCidr:true)
		]
		return networks
	}

	/**
	 * Provides a Collection of {@link NetworkSubnetType} related to this CloudProvider
	 * @return Collection of NetworkSubnetType
	 */
	@Override
	Collection<NetworkSubnetType> getSubnetTypes() {
		Collection<NetworkSubnetType> subnets = []
		return subnets
	}

	/**
	 * Provides a Collection of {@link StorageVolumeType} related to this CloudProvider
	 * @return Collection of StorageVolumeType
	 */
	@Override
	Collection<StorageVolumeType> getStorageVolumeTypes() {
		Collection<StorageVolumeType> volumeTypes = []
		return volumeTypes
	}

	/**
	 * Provides a Collection of {@link StorageControllerType} related to this CloudProvider
	 * @return Collection of StorageControllerType
	 */
	@Override
	Collection<StorageControllerType> getStorageControllerTypes() {
		Collection<StorageControllerType> controllerTypes = []
		return controllerTypes
	}

	/**
	 * Grabs all {@link ComputeServerType} objects that this CloudProvider can represent during a sync or during a provision.
	 * @return collection of ComputeServerType
	 */
	@Override
	Collection<ComputeServerType> getComputeServerTypes() {
		Collection<ComputeServerType> serverTypes = [
			new ComputeServerType(
				code:'selfManagedLinux', name:'Manual Docker Host', description:'', platform:'linux', nodeType:'morpheus-node',
				enabled:true, selectable:false, externalDelete:false, managed:true, controlPower:false, controlSuspend:false, creatable:true, computeService:'standardComputeService',
				displayOrder:16, hasAutomation:true,
				containerHypervisor:true, bareMetalHost:false, vmHypervisor:false, agentType:ComputeServerType.AgentType.host, containerEngine:'docker',
				provisionTypeCode: 'manual',
				computeTypeCode:'docker-host',
				optionTypes:[
					new OptionType(code:'computeServerType.global.sshHost', name: 'sshHost', fieldLabel: 'Host', fieldName: 'sshHost', displayOrder: 1),
					new OptionType(code:'computeServerType.global.sshPort', name: 'sshPort', fieldLabel: 'Port', fieldName: 'sshPort', displayOrder: 2),
					new OptionType(code:'computeServerType.global.sshUsername', name: 'sshUsername', fieldLabel: 'User', fieldName: 'sshUsername', displayOrder: 3),
					new OptionType(code:'computeServerType.global.sshPassword', name: 'sshPassword', fieldLabel: 'Password', fieldName: 'sshPassword', displayOrder: 4),
					new OptionType(code:'computeServerType.global.provisionKey', name: 'provisionKey', fieldLabel: 'SSH Key', fieldName: 'provisionKey', displayOrder: 5),
					new OptionType(code:'computeServerType.global.lvmEnabled', name: 'lvmEnabled', fieldLabel: 'LVM Enabled?', fieldName: 'lvmEnabled', displayOrder: 6),
					new OptionType(code:'computeServerType.global.dataDevice', name: 'dataDevice', fieldLabel: 'Data Volume', fieldName: 'dataDevice', displayOrder: 7),
					new OptionType(code:'computeServerType.global.softwareRaid', name: 'softwareRaid', fieldLabel: 'Software Raid', fieldName: 'softwareRaid', displayOrder: 8),
					new OptionType(code:'computeServerType.global.network.name', name: 'network name', fieldLabel: 'Network Interface', fieldName: 'name', displayOrder: 9)
				]
			),
			new ComputeServerType(
				code:'upcloudWindows', name:'UpCloud Windows Node', description:'', platform:'windows', nodeType:'morpheus-windows-node',
				enabled:true, selectable:false, externalDelete:true, managed:true, controlPower:true, controlSuspend:false, creatable:false, computeService:'upCloudComputeService',
				displayOrder:17, hasAutomation:true,reconfigureSupported: true,
				containerHypervisor:false, bareMetalHost:false, vmHypervisor:false, agentType:ComputeServerType.AgentType.host, guestVm:true,
				provisionTypeCode:'upcloud'
			),
			new ComputeServerType(
				code:'upcloudLinux', name:'UpCloud Docker Host', description:'', platform:'linux', nodeType:'morpheus-node',
				enabled:true, selectable:false, externalDelete:true, managed:true, controlPower:true, controlSuspend:false, creatable:false, computeService:'upCloudComputeService',
				displayOrder: 16, hasAutomation:true,reconfigureSupported: true,
				containerHypervisor:true, bareMetalHost:false, vmHypervisor:false, agentType:ComputeServerType.AgentType.host, containerEngine:'docker',
				provisionTypeCode:'upcloud',
				computeTypeCode:'docker-host'
			),
			new ComputeServerType(
				code:'upcloudVm', name:'UpCloud VM Instance', description:'', platform:'linux', nodeType:'morpheus-vm-node',
				enabled:true, selectable:false, externalDelete:true, managed:true, controlPower:true, controlSuspend:false, creatable:true, computeService:'upCloudComputeService',
				displayOrder: 0, hasAutomation:true,reconfigureSupported: true,
				containerHypervisor:false, bareMetalHost:false, vmHypervisor:false, agentType:ComputeServerType.AgentType.guest, guestVm:true,
				provisionTypeCode:'upcloud'
			),
			new ComputeServerType(
				code:'selfManagedKvm', name:'Manual KVM Host', description:'', platform:'linux', nodeType:'morpheus-node',
				enabled:true, selectable:false, externalDelete:false, managed:true, controlPower:false, controlSuspend:false, creatable:false, computeService:'standardComputeService',
				displayOrder:16, hasAutomation:true,
				containerHypervisor:false, bareMetalHost:false, vmHypervisor:true, agentType:ComputeServerType.AgentType.guest,
				provisionTypeCode: 'manual',
				computeTypeCode: 'kvm-host',
				optionTypes:[
					new OptionType(code:'computeServerType.global.sshHost', name: 'sshHost', fieldLabel: 'Host', fieldName: 'sshHost', displayOrder: 1),
					new OptionType(code:'computeServerType.global.sshPort', name: 'sshPort', fieldLabel: 'Port', fieldName: 'sshPort', displayOrder: 2),
					new OptionType(code:'computeServerType.global.sshUsername', name: 'sshUsername', fieldLabel: 'User', fieldName: 'sshUsername', displayOrder: 3),
					new OptionType(code:'computeServerType.global.sshPassword', name: 'sshPassword', fieldLabel: 'Password', fieldName: 'sshPassword', displayOrder: 4),
					new OptionType(code:'computeServerType.global.provisionKey', name: 'provisionKey', fieldLabel: 'SSH Key', fieldName: 'provisionKey', displayOrder: 5)
				]
			)
		]

		return serverTypes
	}

	/**
	 * Validates the submitted cloud information to make sure it is functioning correctly.
	 * If a {@link ServiceResponse} is not marked as successful then the validation results will be
	 * bubbled up to the user.
	 * @param cloudInfo cloud
	 * @param validateCloudRequest Additional validation information
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse validate(Cloud cloudInfo, ValidateCloudRequest validateCloudRequest) {
		try {
			if(cloudInfo) {
				def username
				def password
				if(validateCloudRequest.credentialType?.toString()?.isNumber()) {
					AccountCredential accountCredential = morpheus.async.accountCredential.get(validateCloudRequest.credentialType.toLong()).blockingGet()
					password = accountCredential.data.password
					username = accountCredential.data.username
				} else if(validateCloudRequest.credentialType == 'username-password') {
					password = validateCloudRequest.credentialPassword ?: cloudInfo.configMap.password ?: cloudInfo.servicePassword
					username = validateCloudRequest.credentialUsername ?: cloudInfo.configMap.username ?: cloudInfo.serviceUsername
				} else if(validateCloudRequest.credentialType == 'local') {
					if(validateCloudRequest.opts?.zone?.servicePassword && validateCloudRequest.opts?.zone?.servicePassword != '************') {
						password = validateCloudRequest.opts?.zone?.servicePassword
					} else {
						password = cloudInfo.configMap.password ?: cloudInfo.servicePassword
					}
					username = validateCloudRequest.opts?.zone?.serviceUsername ?: cloudInfo.configMap.username ?: cloudInfo.serviceUsername
				}

				if(cloudInfo.configMap.zone?.length() < 1) {
					return new ServiceResponse(success: false, msg: 'Choose a zone')
				} else if(username?.length() < 1) {
					return new ServiceResponse(success: false, msg: 'Enter a username')
				} else if(password?.length() < 1) {
					return new ServiceResponse(success: false, msg: 'Enter a password')
				} else {
					def authConfig = plugin.getAuthConfig(cloudInfo)
					def zoneList = UpcloudApiService.listZones(authConfig)
					if(zoneList.success == true) {
						return ServiceResponse.success()
					} else {
						return new ServiceResponse(success: false, msg: 'Invalid Upcloud credentials')
					}
				}
			} else {
				return new ServiceResponse(success: false, msg: 'No zone found')
			}
		} catch(e) {
			log.error("An Exception Has Occurred: ${e.message}", e)
			return new ServiceResponse(success: false, msg: 'Error validating cloud')
		}
	}

	/**
	 * Called when a Cloud From Morpheus is first saved. This is a hook provided to take care of initial state
	 * assignment that may need to take place.
	 * @param cloudInfo instance of the cloud object that is being initialized.
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse initializeCloud(Cloud cloudInfo) {
		ServiceResponse rtn = new ServiceResponse(success: false)
		try {
			if(cloudInfo) {
				if(cloudInfo.enabled == true) {
					refreshDaily(cloudInfo)
					refresh(cloudInfo)
					rtn = ServiceResponse.success()
				}
			} else {
				rtn = ServiceResponse.error('No zone found')
			}
		} catch(e) {
			e.printStackTrace()
			log.error("An Exception Has Occurred: ${e.message}",e)
		}
		return rtn
	}

	/**
	 * Zones/Clouds are refreshed periodically by the Morpheus Environment. This includes things like caching of brownfield
	 * environments and resources such as Networks, Datastores, Resource Pools, etc.
	 * @param cloudInfo cloud
	 * @return ServiceResponse. If ServiceResponse.success == true, then Cloud status will be set to Cloud.Status.ok. If
	 * ServiceResponse.success == false, the Cloud status will be set to ServiceResponse.data['status'] or Cloud.Status.error
	 * if not specified. So, to indicate that the Cloud is offline, return `ServiceResponse.error('cloud is not reachable', null, [status: Cloud.Status.offline])`
	 */
	@Override
	ServiceResponse refresh(Cloud cloudInfo) {
		ServiceResponse rtn = new ServiceResponse(success: false)

		HttpApiClient client

		try {
			NetworkProxy proxySettings = cloudInfo.apiProxy
			client = new HttpApiClient()
			client.networkProxy = proxySettings

			def authConfig = plugin.getAuthConfig(cloudInfo)
			def apiUrlObj = new URL(authConfig.apiUrl)
			def apiHost = apiUrlObj.getHost()
			def apiPort = apiUrlObj.getPort() > 0 ? apiUrlObj.getPort() : (apiUrlObj?.getProtocol()?.toLowerCase() == 'https' ? 443 : 80)
			def hostOnline = ConnectionUtils.testHostConnectivity(apiHost, apiPort, false, true, proxySettings)
			if(hostOnline) {
				def testResults = UpcloudStatusUtility.testConnection(authConfig)
				if(testResults.success == true) {
					//def doInventory = cloudInfo.getConfigProperty('importExisting')
					//def vmCacheOpts = [zone:zone, createNew:(inventoryLevel == 'basic' || inventoryLevel == 'full'), inventoryLevel:inventoryLevel]

					(new UserImagesSync(cloudInfo, this.plugin, context)).execute()
					(new VirtualMachinesSync(cloudInfo, this.plugin, context)).execute()

					rtn = ServiceResponse.success()
				} else {
					rtn = ServiceResponse.error(testResults.invalidLogin == true ? 'invalid credentials' : 'error connecting', null, [status: Cloud.Status.offline])
				}
			} else {
				rtn = ServiceResponse.error('upcloud not reachable', null, [status: Cloud.Status.offline])
			}
//			ProvisionType upCloudProvType = ProvisionType.findByCode('upcloud')
//			updateLayoutsForScale(upCloudProvType)
			rtn = ServiceResponse.success()
		} catch (e) {
			log.error("refresh cloud error: ${e}", e)
		} finally {
			if(client) {
				client.shutdownClient()
			}
		}

		return rtn
	}

	/**
	 * Zones/Clouds are refreshed periodically by the Morpheus Environment. This includes things like caching of brownfield
	 * environments and resources such as Networks, Datastores, Resource Pools, etc. This represents the long term sync method that happens
	 * daily instead of every 5-10 minute cycle
	 * @param cloudInfo cloud
	 */
	@Override
	void refreshDaily(Cloud cloudInfo) {
		try {
			//(new PlansSync(this.plugin, cloudInfo)).execute()
			(new PublicTemplatesSync(cloudInfo, this.plugin, context)).execute()
		} catch(e) {
			log.error("refreshZone error: ${e}", e)
		}
	}

	/**
	 * Called when a Cloud From Morpheus is removed. This is a hook provided to take care of cleaning up any state.
	 * @param cloudInfo instance of the cloud object that is being removed.
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse deleteCloud(Cloud cloudInfo) {
		return ServiceResponse.success()
	}

	/**
	 * Returns whether the cloud supports {@link CloudPool}
	 * @return Boolean
	 */
	@Override
	Boolean hasComputeZonePools() {
		return false
	}

	/**
	 * Returns whether a cloud supports {@link Network}
	 * @return Boolean
	 */
	@Override
	Boolean hasNetworks() {
		return true
	}

	/**
	 * Returns whether a cloud supports {@link CloudFolder}
	 * @return Boolean
	 */
	@Override
	Boolean hasFolders() {
		return false
	}

	/**
	 * Returns whether a cloud supports {@link Datastore}
	 * @return Boolean
	 */
	@Override
	Boolean hasDatastores() {
		return false
	}

	/**
	 * Returns whether a cloud supports bare metal VMs
	 * @return Boolean
	 */
	@Override
	Boolean hasBareMetal() {
		return false
	}

	/**
	 * Indicates if the cloud supports cloud-init. Returning true will allow configuration of the Cloud
	 * to allow installing the agent remotely via SSH /WinRM or via Cloud Init
	 * @return Boolean
	 */
	@Override
	Boolean hasCloudInit() {
		return false
	}

	/**
	 * Indicates if the cloud supports the distributed worker functionality
	 * @return Boolean
	 */
	@Override
	Boolean supportsDistributedWorker() {
		return false
	}

	/**
	 * Called when a server should be started. Returning a response of success will cause corresponding updates to usage
	 * records, result in the powerState of the computeServer to be set to 'on', and related instances set to 'running'
	 * @param computeServer server to start
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse startServer(ComputeServer computeServer) {
		return ServiceResponse.success()
	}

	/**
	 * Called when a server should be stopped. Returning a response of success will cause corresponding updates to usage
	 * records, result in the powerState of the computeServer to be set to 'off', and related instances set to 'stopped'
	 * @param computeServer server to stop
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse stopServer(ComputeServer computeServer) {
		return ServiceResponse.success()
	}

	/**
	 * Called when a server should be deleted from the Cloud.
	 * @param computeServer server to delete
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse deleteServer(ComputeServer computeServer) {
		return ServiceResponse.success()
	}

	/**
	 * Grabs the singleton instance of the provisioning provider based on the code defined in its implementation.
	 * Typically Providers are singleton and instanced in the {@link Plugin} class
	 * @param providerCode String representation of the provider short code
	 * @return the ProvisionProvider requested
	 */
	@Override
	ProvisionProvider getProvisionProvider(String providerCode) {
		return getAvailableProvisionProviders().find { it.code == providerCode }
	}

	/**
	 * Returns the default provision code for fetching a {@link ProvisionProvider} for this cloud.
	 * This is only really necessary if the provision type code is the exact same as the cloud code.
	 * @return the provision provider code
	 */
	@Override
	String getDefaultProvisionTypeCode() {
		return UpcloudProvisionProvider.PROVISION_PROVIDER_CODE
	}

	/**
	 * Returns the Morpheus Context for interacting with data stored in the Main Morpheus Application
	 * @return an implementation of the MorpheusContext for running Future based rxJava queries
	 */
	@Override
	MorpheusContext getMorpheus() {
		return this.@context
	}

	/**
	 * Returns the instance of the Plugin class that this provider is loaded from
	 * @return Plugin class contains references to other providers
	 */
	@Override
	Plugin getPlugin() {
		return this.@plugin
	}

	/**
	 * A unique shortcode used for referencing the provided provider. Make sure this is going to be unique as any data
	 * that is seeded or generated related to this provider will reference it by this code.
	 * @return short code string that should be unique across all other plugin implementations.
	 */
	@Override
	String getCode() {
		return 'upcloud'
	}

	/**
	 * Provides the provider name for reference when adding to the Morpheus Orchestrator
	 * NOTE: This may be useful to set as an i18n key for UI reference and localization support.
	 *
	 * @return either an English name of a Provider or an i18n based key that can be scanned for in a properties file.
	 */
	@Override
	String getName() {
		return 'Upcloud'
	}
}
