package com.morpheusdata.upcloud

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.CloudProvider
import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.model.BackupProvider
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudFolder
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.Icon
import com.morpheusdata.model.Network
import com.morpheusdata.model.NetworkSubnetType
import com.morpheusdata.model.NetworkType
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.StorageControllerType
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.request.ValidateCloudRequest
import com.morpheusdata.response.ServiceResponse

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
				fieldName:'type', fieldCode:'gomorpheus.label.credentials', fieldLabel:'Credentials', fieldContext:'credential', fieldSet:'', fieldGroup:'Connection Config', required:true, enabled:true, editable:true, global:false,
				placeHolder:null, helpBlock:'', defaultValue:'local', custom:false, displayOrder:2, fieldClass:null, optionSource:'credentials', config: JsonOutput.toJson(credentialTypes:['username-password']).toString()),
			new OptionType(code:'zoneType.upcloud.username', inputType:OptionType.InputType.TEXT, name:'Username', category:'zoneType.upcloud',
				fieldName:'username', fieldCode: 'gomorpheus.optiontype.Username', fieldLabel:'Username', fieldContext:'config', fieldSet:'', fieldGroup:'Connection Config', required:true, enabled:true, editable:false, global:false,
				placeHolder:null, helpBlock:'', defaultValue:null, custom:false, displayOrder:2, fieldClass:null, fieldSize:15, localCredential:true),
			new OptionType(code:'zoneType.upcloud.password', inputType:OptionType.InputType.PASSWORD, name:'Password', category:'zoneType.upcloud',
				fieldName:'password', fieldCode: 'gomorpheus.optiontype.Password', fieldLabel:'Password', fieldContext:'config', fieldSet:'', fieldGroup:'Connection Config', required:true, enabled:true, editable:false, global:false,
				placeHolder:null, helpBlock:'', defaultValue:null, custom:false, displayOrder:3, fieldClass:null, fieldSize:25, localCredential:true),
			new OptionType(code:'zoneType.upcloud.zone', inputType:OptionType.InputType.TEXT, name:'Zone', category:'zoneType.upcloud',
				fieldName:'zone', fieldCode: 'gomorpheus.optiontype.Zone', fieldLabel:'Zone', fieldContext:'config', fieldSet:'', fieldGroup:'Connection Config', required:true, enabled:true, editable:false, global:false,
				placeHolder:null, helpBlock:'', defaultValue:null, custom:false, displayOrder:4, fieldClass:null, fieldSize:15)
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
					new OptionType(code:'computeServerType.global.sshHost'),
					new OptionType(code:'computeServerType.global.sshPort'),
					new OptionType(code:'computeServerType.global.sshUsername'),
					new OptionType(code:'computeServerType.global.sshPassword'),
					new OptionType(code:'computeServerType.global.provisionKey'),
					new OptionType(code:'computeServerType.global.lvmEnabled'),
					new OptionType(code:'computeServerType.global.dataDevice'),
					new OptionType(code:'computeServerType.global.softwareRaid'),
					new OptionType(code:'computeServerType.global.network.name')
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
					new OptionType(code:'computeServerType.global.sshHost'),
					new OptionType(code:'computeServerType.global.sshPort'),
					new OptionType(code:'computeServerType.global.sshUsername'),
					new OptionType(code:'computeServerType.global.sshPassword'),
					new OptionType(code:'computeServerType.global.provisionKey')
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
		return ServiceResponse.success()
	}

	/**
	 * Called when a Cloud From Morpheus is first saved. This is a hook provided to take care of initial state
	 * assignment that may need to take place.
	 * @param cloudInfo instance of the cloud object that is being initialized.
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse initializeCloud(Cloud cloudInfo) {
		return ServiceResponse.success()
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
		return ServiceResponse.success()
	}

	/**
	 * Zones/Clouds are refreshed periodically by the Morpheus Environment. This includes things like caching of brownfield
	 * environments and resources such as Networks, Datastores, Resource Pools, etc. This represents the long term sync method that happens
	 * daily instead of every 5-10 minute cycle
	 * @param cloudInfo cloud
	 */
	@Override
	void refreshDaily(Cloud cloudInfo) {
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
