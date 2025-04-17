package com.morpheusdata.upcloud

import com.morpheusdata.core.AbstractProvisionProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.core.providers.WorkloadProvisionProvider
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudRegion
import com.morpheusdata.model.ComputeCapacityInfo
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.ComputeServerInterfaceType
import com.morpheusdata.model.ComputeTypeSet
import com.morpheusdata.model.HostType
import com.morpheusdata.model.Icon
import com.morpheusdata.model.Instance
import com.morpheusdata.model.NetAddress
import com.morpheusdata.model.NetworkProxy
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.ProxyConfiguration
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.Workload
import com.morpheusdata.model.WorkloadType
import com.morpheusdata.model.provisioning.HostRequest
import com.morpheusdata.model.provisioning.NetworkConfiguration
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.request.ResizeRequest
import com.morpheusdata.response.PrepareWorkloadResponse
import com.morpheusdata.response.ProvisionResponse
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.upcloud.datasets.UpcloudImageDatasetProvider
import com.morpheusdata.upcloud.services.UpcloudApiService
import groovy.util.logging.Slf4j
import org.apache.tools.ant.types.spi.Service

@Slf4j
class UpcloudProvisionProvider extends AbstractProvisionProvider implements WorkloadProvisionProvider, ProvisionProvider.BlockDeviceNameFacet {
	public static final String PROVISION_PROVIDER_CODE = 'upcloud.provision'

	protected MorpheusContext context
	protected UpcloudPlugin plugin

	public UpcloudProvisionProvider(UpcloudPlugin plugin, MorpheusContext ctx) {
		super()
		this.@context = ctx
		this.@plugin = plugin
	}

	/**
	 * This method is called before runWorkload and provides an opportunity to perform action or obtain configuration
	 * that will be needed in runWorkload. At the end of this method, if deploying a ComputeServer with a VirtualImage,
	 * the sourceImage on ComputeServer should be determined and saved.
	 * @param workload the Workload object we intend to provision along with some of the associated data needed to determine
	 *                 how best to provision the workload
	 * @param workloadRequest the RunWorkloadRequest object containing the various configurations that may be needed
	 *                        in running the Workload. This will be passed along into runWorkload
	 * @param opts additional configuration options that may have been passed during provisioning
	 * @return Response from API
	 */
	@Override
	ServiceResponse<PrepareWorkloadResponse> prepareWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		ServiceResponse<PrepareWorkloadResponse> resp = new ServiceResponse<PrepareWorkloadResponse>(
			true, // successful
			'', // no message
			null, // no errors
			new PrepareWorkloadResponse(workload:workload) // adding the workload to the response for convenience
		)
		return resp
	}

	/**
	 * Some older clouds have a provision type code that is the exact same as the cloud code. This allows one to set it
	 * to match and in doing so the provider will be fetched via the cloud providers {@link CloudProvider#getDefaultProvisionTypeCode()} method.
	 * @return code for overriding the ProvisionType record code property
	 */
	@Override
	String getProvisionTypeCode() {
		return 'upcloud'
	}

	/**
	 * Provide an icon to be displayed for ServicePlans, VM detail page, etc.
	 * where a circular icon is displayed
	 * @since 0.13.6
	 * @return Icon
	 */
	@Override
	Icon getCircularIcon() {
		// TODO: change icon paths to correct filenames once added to your project
		return new Icon(path:'provision-circular.svg', darkPath:'provision-circular-dark.svg')
	}

	/**
	 * Provides a Collection of OptionType inputs that need to be made available to various provisioning Wizards
	 * @return Collection of OptionTypes
	 */
	@Override
	Collection<OptionType> getOptionTypes() {
		Collection<OptionType> options = [
			new OptionType(code:"provisionType.${this.getCode()}.noAgent", inputType:OptionType.InputType.CHECKBOX, name:'skip agent install', category:"provisionType.${this.getCode()}",
					fieldName:'noAgent', fieldCode: 'gomorpheus.optiontype.SkipAgentInstall', fieldLabel:'Skip Agent Install', fieldContext:'config', fieldGroup:'Advanced Options', required:false, enabled:true,
					editable:false, global:false, placeHolder:null, helpBlock:'Skipping Agent installation will result in a lack of logging and guest operating system statistics. Automation scripts may also be adversely affected.', defaultValue:null, custom:false, displayOrder:4, fieldClass:null),
//			new OptionType(code:'containerType.upcloud.imageId', inputType:OptionType.InputType.SELECT, name:'imageType', category:'containerType.upcloud', optionSource: 'upcloudImage', optionSourceType:'upcloud',
//					fieldName:'imageId', fieldCode: 'gomorpheus.optiontype.Image', fieldLabel:'Image', fieldContext:'config', required:true, enabled:true, editable:false, global:false,
//					placeHolder:null, helpBlock:'', defaultValue:null, custom:false, displayOrder:3, fieldClass:null)
		]
		// TODO: create some option types for provisioning and add them to collection
		return options
	}

	/**
	 * Provides a Collection of OptionType inputs for configuring node types
	 * @since 0.9.0
	 * @return Collection of OptionTypes
	 */
	@Override
	Collection<OptionType> getNodeOptionTypes() {
		OptionType virtualImageTypeOption = new OptionType([
				name: 'virtual image type',
				code:'upcloud-node-virtual-image-type',
				fieldContext: 'config',
				fieldName: 'virtualImageSelect',
				fieldCode: null,
				fieldLabel: null,
				fieldGroup: null,
				inputType: OptionType.InputType.RADIO,
				displayOrder:98,
				fieldClass:'inline',
				required: false,
				editable: true,
				optionSource: 'virtualImageTypeList'
		])
		OptionType osTypeOption = new OptionType([
				name : 'osType',
				code : 'upcloud-node-os-type',
				fieldName : 'osType.id',
				fieldContext : 'domain',
				fieldLabel : 'OsType',
				inputType : OptionType.InputType.SELECT,
				displayOrder : 100,
				required : false,
				optionSource : 'osTypes',
				noSelection: 'Select',
				visibleOnCode: 'config.virtualImageSelect:os'
		])
		OptionType imageOption = new OptionType([
				name : 'image',
				code : 'upcloud-node-image',
				fieldName : 'virtualImage.id',
				fieldContext : 'domain',
				fieldLabel : 'Image',
				inputType : OptionType.InputType.SELECT,
				displayOrder : 99,
				required : false,
				optionSource : 'upcloud.upcloudImageDataset',
				visibleOnCode: 'config.virtualImageSelect:vi'
		])
		OptionType logFolder = new OptionType([
				name : 'mountLogs',
				code : 'upcloud-node-log-folder',
				fieldName : 'mountLogs',
				fieldContext : 'domain',
				fieldLabel : 'Log Folder',
				inputType : OptionType.InputType.TEXT,
				displayOrder : 101,
				required : false,
		])
		OptionType configFolder = new OptionType([
				name : 'mountConfig',
				code : 'upcloud-node-config-folder',
				fieldName : 'mountConfig',
				fieldContext : 'domain',
				fieldLabel : 'Config Folder',
				inputType : OptionType.InputType.TEXT,
				displayOrder : 102,
				required : false,
		])
		OptionType deployFolder = new OptionType([
				name : 'mountData',
				code : 'upcloud-node-deploy-folder',
				fieldName : 'mountData',
				fieldContext : 'domain',
				fieldLabel : 'Deploy Folder',
				inputType : OptionType.InputType.TEXT,
				displayOrder : 103,
				helpText: '(Optional) If using deployment services, this mount point will be replaced with the contents of said deployments.',
				required : false,
		])
		return [virtualImageTypeOption, osTypeOption, imageOption, logFolder, configFolder, deployFolder]
	}

	/**
	 * Provides a Collection of StorageVolumeTypes that are available for root StorageVolumes
	 * @return Collection of StorageVolumeTypes
	 */
	@Override
	Collection<StorageVolumeType> getRootVolumeStorageTypes() {
		Collection<StorageVolumeType> volumeTypes = [
			new StorageVolumeType(code:'upcloudVolume', displayName:'UpCloud MaxIOPS', name:'MaxIOPS', description:'UpCloud MaxIOPS', volumeType:'disk', enabled:true,
				displayOrder:1, customLabel:true, customSize:true, defaultType:true, autoDelete:true, minStorage:(10L * ComputeUtility.ONE_GIGABYTE), allowSearch:true, volumeCategory:'disk')
		]
		// TODO: create some storage volume types and add to collection
		return volumeTypes
	}

	/**
	 * Provides a Collection of StorageVolumeTypes that are available for data StorageVolumes
	 * @return Collection of StorageVolumeTypes
	 */
	@Override
	Collection<StorageVolumeType> getDataVolumeStorageTypes() {
		Collection<StorageVolumeType> dataVolTypes = [
			new StorageVolumeType(code:'upcloudVolume', displayName:'UpCloud MaxIOPS', name:'MaxIOPS', description:'UpCloud MaxIOPS', volumeType:'disk', enabled:true,
				displayOrder:1, customLabel:true, customSize:true, defaultType:true, autoDelete:true, minStorage:(10L * ComputeUtility.ONE_GIGABYTE), allowSearch:true, volumeCategory:'disk')
		]
		// TODO: create some data volume types and add to collection
		return dataVolTypes
	}

	/**
	 * Provides a Collection of ${@link ServicePlan} related to this ProvisionProvider that can be seeded in.
	 * Some clouds do not use this as they may be synced in from the public cloud. This is more of a factor for
	 * On-Prem clouds that may wish to have some precanned plans provided for it.
	 * @return Collection of ServicePlan sizes that can be seeded in at plugin startup.
	 */
	@Override
	Collection<ServicePlan> getServicePlans() {
		Collection<ServicePlan> plans = []
		// TODO: create some service plans (sizing like cpus, memory, etc) and add to collection
		return plans
	}

	/**
	 * Validates the provided provisioning options of a workload. A return of success = false will halt the
	 * creation and display errors
	 * @param opts options
	 * @return Response from API. Errors should be returned in the errors Map with the key being the field name and the error
	 * message as the value.
	 */
	@Override
	ServiceResponse validateWorkload(Map opts) {
		return ServiceResponse.success()
	}

	Boolean canAddVolumes() {
		return true
	}

	Boolean canCustomizeDataVolumes() {
		return true
	}

	@Override
	Boolean createDefaultInstanceType() {
		return false
	}

	@Override
	HostType getHostType() {
		return HostType.vm
	}

	def buildDataDisk(volume) {
		return [id:volume.id, diskType:volume?.type?.code ?: 'upcloudVolume', maxStorage:volume.maxStorage, name:volume.name,
				deviceName:volume.deviceName, displayOrder:volume.displayOrder]
	}

	def buildDataDisks(volumes) {
		def rtn = []
		volumes?.each { volume ->
			rtn << buildDataDisk(volume)
		}
		return rtn
	}

	def cleanInstanceName(name) {
		def rtn = name.replaceAll(/[^a-zA-Z0-9\.\-]/,'')
		return rtn
	}

	def insertImage(Map runConfig, Map opts) {
		def taskResults = [success:false, imageId:runConfig.virtualImage.id, virtualImage:runConfig.virtualImage]
		try {
			taskResults.success = true
		} catch(imageException) {
			log.error("imageException: ${imageException}", imageException)
			taskResults.message = 'Error uploading image'
		}
		return taskResults
	}

	/**
	 * This method is a key entry point in provisioning a workload. This could be a vm, a container, or something else.
	 * Information associated with the passed Workload object is used to kick off the workload provision request
	 * @param workload the Workload object we intend to provision along with some of the associated data needed to determine
	 *                 how best to provision the workload
	 * @param workloadRequest the RunWorkloadRequest object containing the various configurations that may be needed
	 *                        in running the Workload
	 * @param opts additional configuration options that may have been passed during provisioning
	 * @return Response from API
	 */
	@Override
	ServiceResponse<ProvisionResponse> runWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		log.debug "runWorkload: ${workload} ${workloadRequest} ${opts}"
		ProvisionResponse provisionResponse = new ProvisionResponse(success: true)

//		ComputeServer server = workload.server
//		try {
//			Cloud cloud = server.cloud
//			VirtualImage virtualImage = server.sourceImage
//			def runConfig = buildWorkloadRunConfig(workload, workloadRequest, virtualImage, opts)
//
//			runVirtualMachine(runConfig, provisionResponse, opts)
//			log.info("Checking Server Interfaces....")
//			workload.server.interfaces?.each { netInt ->
//				log.info("Net Interface: ${netInt.id} -> Network: ${netInt.network?.id}")
//			}
//			provisionResponse.noAgent = opts.noAgent ?: false
//			return new ServiceResponse<ProvisionResponse>(success: true, data: provisionResponse)
//		} catch (e) {
//			log.error "runWorkload: ${e}", e
//			provisionResponse.setError(e.message)
//			return new ServiceResponse(success: false, msg: e.message, error: e.message, data: provisionResponse)
//		}

		try {
			def containerConfig = workload.getConfigMap()
			def server = workload.server
			def cloud = server.cloud
			def account = server.account
			def cloudConfig = cloud.getConfigMap()

			def authConfig = plugin.getAuthConfig(cloud)
			opts.noAgent = containerConfig.noAgent
			opts.findAdminPassword = true
			def imageType = containerConfig.imageType ?: 'default'
			def virtualImageId = (containerConfig.imageId?.toLong() ?: containerConfig.template?.toLong() ?: server.sourceImage.id)
			def virtualImage = morpheus.async.virtualImage.get(virtualImageId).blockingGet()
			if(virtualImage) {
				def rootVolume = workload.server.volumes?.find{ it.rootVolume == true}
				def dataDisks = workload.server.volumes?.findAll{ it.rootVolume == false}?.sort{it.id}
				def servicePlan = workload.instance.plan
				def maxMemory = server.maxMemory?.div(ComputeUtility.ONE_MEGABYTE)
				def maxStorage = rootVolume?.getMaxStorage() ?: opts.config?.maxStorage ?: server.plan.maxStorage

				def runConfig = [
						containerId: workload.id,
						instanceId: workload.instance.id,
						account: account,
						zone: cloud,
						name: cleanInstanceName(server.name),
						maxStorage: maxStorage,
						maxMemory: maxMemory,
						applianceServerUrl: workloadRequest.cloudConfigOpts?.applianceUrl,
						workloadConfig: workload.getConfigMap(),
						timezone: (server.getConfigProperty('timezone') ?: cloud.timezone),
						zoneRef: cloudConfig.zone,
						hostname: server.getExternalHostname(),
						userData: null,
						externalId: server.externalId,
						serverId: server.id,
						virtualImage: virtualImage,
						dataDisks: dataDisks,
						rootVolume: rootVolume,
						container: workload,
						callbackService: opts.callbackService,
						server: workload.server,
						diskList: [],
						domainName: server.getExternalDomain(),
						authConfig: authConfig,
						serverInterfaces: server.interfaces,
						osType: (virtualImage.osType?.platform?.toString() == 'windows' ? 'windows' : 'linux') ?: virtualImage.platform,
						platform: (virtualImage.osType?.platform?.toString() == 'windows' ? 'windows' : 'linux') ?: virtualImage.platform,
						proxySettings: workloadRequest.proxyConfiguration,
						userConfig: workloadRequest.usersConfiguration,
						cloudConfig: workloadRequest.cloudConfigUser,
						networkConfig: workloadRequest.networkConfiguration,
						noAgent: (opts.config?.containsKey("noAgent") == true && opts.config.noAgent == true),
						installAgent: (opts.config?.containsKey("noAgent") == false || (opts.config?.containsKey("noAgent") && opts.config.noAgent != true))
				]

				if(servicePlan.internalId == 'custom') {
					runConfig.maxMemory = workload.maxMemory ?: servicePlan.maxMemory
					runConfig.maxCores = workload.maxCores ?: servicePlan.maxCores
				} else {
					runConfig.planRef = servicePlan.externalId
					runConfig.tier = servicePlan.getConfigProperty('tier')
				}
				runConfig.fqdn = runConfig.hostName + '.' + runConfig.domainName

				if(opts.cloneContainerId && opts.backupSetId) {
					def snapshot = morpheus.services.backup.backupResult.find(
							new DataQuery().withFilter("backupSetId", opts.backupSetId)
							.withFilter("containerId", opts.cloneContainerId))
					def snapshots = snapshot.getConfigProperty("snapshots")
					def rootSnapshot = snapshots?.find{ it.root == true }
					if(rootSnapshot && rootSnapshot.storageId) {
						runConfig.cloneImageId = rootSnapshot.storageId
					}
					// Handle any data disks
					def dataSnapshots = snapshots?.findAll { !it.root }
					dataSnapshots?.each { dataSnapshot ->
						def dataDisk = runConfig.dataDisks?.find { !it.getConfigProperty("snapshotUUID") && (int)it.maxStorage.div(ComputeUtility.ONE_GIGABYTE) == (int)dataSnapshot.sizeInGb }
						dataDisk.setConfigProperty("snapshotUUID",dataSnapshot.storageId)
					}
				}
				// upload or insert image
				def imageUploadResults = insertImage(runConfig, opts)

				if(imageUploadResults.success == true) {
					try {
						if (imageUploadResults.success == true && imageUploadResults.imageId) {
							runConfig.virtualImage = morpheus.async.virtualImage.get(imageUploadResults?.virtualImage?.id ?: runConfig.virtualImage.id).blockingGet()
							runConfig.imageRef = runConfig.virtualImage.externalId
							opts.installAgent = opts.noAgent ? false : runConfig.virtualImage.installAgent
							provisionResponse.noAgent = runConfig.noAgent
							provisionResponse.installAgent = runConfig.installAgent

							runVirtualMachine(runConfig, provisionResponse, opts)
						} else {
							provisionResponse.setError(imageUploadResults.message)
							return new ServiceResponse(success: false, msg: imageUploadResults.message, e: null, data: provisionResponse)
						}
					} catch (e) {
						provisionResponse.setError("failed to acquire additional virtual image information: ${e}")
						return new ServiceResponse(success: false, msg: "failed to acquire additional virtual image information: ${e}", error: e, data: provisionResponse)
					}
				} else {
					log.error("image upload task error: ${imageUploadResults.message}")
					provisionResponse.setError("failed to upload image file")
					return new ServiceResponse(success: false, msg: 'failed to upload image file', error: null, data: provisionResponse)
				}

			} else {
				provisionResponse.setError("virtual image not found")
				return new ServiceResponse(success: false, msg: 'virtual image not found', error: null, data: provisionResponse)
			}

			if(provisionResponse.success != true) {
				return new ServiceResponse(success: false, msg: provisionResponse.message ?: 'vm config error', error: provisionResponse.message, data: provisionResponse)
			} else {
//				provisionResponse.noAgent = true
//				provisionResponse.installAgent = false
				return new ServiceResponse<ProvisionResponse>(success: true, data: provisionResponse)
			}
		} catch (e) {
			log.error "runWorkload error: ${e}", e
			provisionResponse.setError("Failed to create server: ${e.message}")
			return new ServiceResponse(success: false, msg: e.message, error: e, data: provisionResponse)
		}
	}

	/**
	 * This method is called after successful completion of runWorkload and provides an opportunity to perform some final
	 * actions during the provisioning process. For example, ejected CDs, cleanup actions, etc
	 * @param workload the Workload object that has been provisioned
	 * @return Response from the API
	 */
	@Override
	ServiceResponse finalizeWorkload(Workload workload) {
		return ServiceResponse.success()
	}

	/**
	 * Issues the remote calls necessary top stop a workload element from running.
	 * @param workload the Workload we want to shut down
	 * @return Response from API
	 */
	@Override
	ServiceResponse stopWorkload(Workload workload) {
		def rtn = ServiceResponse.prepare()
		try {
			if(workload.server?.externalId) {
				def authConfigMap = plugin.getAuthConfig(workload.server?.cloud)
				def statusResults = UpcloudApiService.waitForServerNotStatus(authConfigMap, workload.server.externalId, 'maintenance')
				if(statusResults.success) {
					def stopResults = stopServer(workload.server)
					if(stopResults.success == true) {
						rtn.success = true
					}
				} else if(statusResults.success == false) {
					rtn.errorCode = statusResults.errorCode
					rtn.msg = 'stopWorkload: failed to get server status'
				}

			} else {
				rtn.success = true
				rtn.msg = 'stopWorkload: vm not found'
			}
		} catch (e) {
			log.error("stopContainer error: ${e}", e)
			rtn.msg = 'stopWorkload: error stopping workload'
		}
		return rtn
	}

	/**
	 * Issues the remote calls necessary to start a workload element for running.
	 * @param workload the Workload we want to start up.
	 * @return Response from API
	 */
	@Override
	ServiceResponse startWorkload(Workload workload) {
		log.debug("startWorkload: ${workload.id}")
		def rtn = ServiceResponse.prepare()
		try {
			if(workload.server?.externalId) {
				def authConfigMap = plugin.getAuthConfig(workload.server?.cloud)
				def statusResults = UpcloudApiService.waitForServerNotStatus(authConfigMap, workload.server.externalId, 'maintenance')
				def startResults = startServer(workload.server)
				log.debug("startWorkload: startResults: ${startResults}")
				if(startResults.success == true) {
					rtn.success = true
				} else {
					rtn.msg = "${startResults.msg}" ?: 'Failed to start vm'
				}
			} else {
				log.debug("startWorkload: vm not found")
			}
		} catch(e) {
			log.error("startContainer error: ${e}", e)
			rtn.error = 'startWorkload: error starting workload'
		}

		return rtn
	}

	/**
	 * Issues the remote calls to restart a workload element. In some cases this is just a simple alias call to do a stop/start,
	 * however, in some cases cloud providers provide a direct restart call which may be preferred for speed.
	 * @param workload the Workload we want to restart.
	 * @return Response from API
	 */
	@Override
	ServiceResponse restartWorkload(Workload workload) {
		// Generally a call to stopWorkLoad() and then startWorkload()
		return ServiceResponse.success()
	}

	/**
	 * This is the key method called to destroy / remove a workload. This should make the remote calls necessary to remove any assets
	 * associated with the workload.
	 * @param workload to remove
	 * @param opts map of options
	 * @return Response from API
	 */
	@Override
	ServiceResponse removeWorkload(Workload workload, Map opts) {
		log.debug "removeWorkload: ${workload} ${opts}"
		ComputeServer server = workload.server
		Cloud cloud = server.cloud
		if(workload.server?.externalId) {
			def authConfig = plugin.getAuthConfig(cloud)
			def stopWorkloadResult =  stopWorkload(workload)
			if(stopWorkloadResult.success) {
				def statusResult = UpcloudApiService.waitForServerStatus(authConfig, server.externalId, 'stopped')
				if(statusResult.success == true) {
					def removeResults = UpcloudApiService.removeServer(authConfig, server.externalId)
					if(removeResults.success == true) {
						server.volumes?.each { volume ->
							if(volume.externalId) {
								def volumeResults = UpcloudApiService.removeStorage(authConfig, volume.externalId)
							}
						}
						return ServiceResponse.success()
					} else {
						return ServiceResponse.error('Failed to remove vm')
					}
				} else {
					return ServiceResponse.error('Failed to remove vm')
				}
			} else if(stopWorkloadResult.success == false && stopWorkloadResult.errorCode == '404') {
				return ServiceResponse.success()
			} else {
				return ServiceResponse.error('Failed to stop vm')
			}
		} else {
			return ServiceResponse.error('vm not found')
		}
	}

	/**
	 * Method called after a successful call to runWorkload to obtain the details of the ComputeServer. Implementations
	 * should not return until the server is successfully created in the underlying cloud or the server fails to
	 * create.
	 * @param server to check status
	 * @return Response from API. The publicIp and privateIp set on the WorkloadResponse will be utilized to update the ComputeServer
	 */
	@Override
	ServiceResponse<ProvisionResponse> getServerDetails(ComputeServer server) {
		return new ServiceResponse<ProvisionResponse>(true, null, null, new ProvisionResponse(success:true))
	}

	/**
	 * Method called before runWorkload to allow implementers to create resources required before runWorkload is called
	 * @param workload that will be provisioned
	 * @param opts additional options
	 * @return Response from API
	 */
	@Override
	ServiceResponse createWorkloadResources(Workload workload, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Stop the server
	 * @param computeServer to stop
	 * @return Response from API
	 */
	@Override
	ServiceResponse stopServer(ComputeServer computeServer) {
		if(computeServer.managed == true || computeServer.computeServerType?.controlPower) {
			def authConfig = plugin.getAuthConfig(computeServer.cloud)
			def statusResults = UpcloudApiService.waitForServerNotStatus(authConfig, computeServer.externalId, 'maintenance')
			def stopResults = UpcloudApiService.stopServer(authConfig, computeServer.externalId)
			if (stopResults.success) {
				def waitResults = UpcloudApiService.waitForServerStatus(authConfig, computeServer.externalId, 'stopped')
				if(waitResults.success) {
					return ServiceResponse.success()
				} else {
					return ServiceResponse.error('Failed to stop vm')
				}
			} else {
				return ServiceResponse.error('Failed to stop vm')
			}
		} else {
			log.debug("stopServer - ignoring request for unmanaged instance")
		}
		return ServiceResponse.success()
	}

	/**
	 * Start the server
	 * @param computeServer to start
	 * @return Response from API
	 */
	@Override
	ServiceResponse startServer(ComputeServer computeServer) {
		if(computeServer.managed == true || computeServer.computeServerType?.controlPower) {
			def authConfig = plugin.getAuthConfig(computeServer.cloud)
			def statusResults = UpcloudApiService.waitForServerNotStatus(authConfig, computeServer.externalId, 'maintenance')
			def startResults = UpcloudApiService.startServer(authConfig, computeServer.externalId)
			if(startResults.success == true) {
				def waitResults = UpcloudApiService.waitForServerStatus(authConfig, computeServer.externalId, 'started')
				if(waitResults.success == true) {
					return ServiceResponse.success()
				} else {
					return ServiceResponse.error('Failed to start vm')
				}
			} else {
				return ServiceResponse.error('Failed to start vm')
			}
		} else {
			log.debug("startServer - ignoring request for unmanaged instance")
		}
		return ServiceResponse.success()
	}

	/**
	 * Returns the Morpheus Context for interacting with data stored in the Main Morpheus Application
	 *
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
		return PROVISION_PROVIDER_CODE
	}

	/**
	 * Provides the provider name for reference when adding to the Morpheus Orchestrator
	 * NOTE: This may be useful to set as an i18n key for UI reference and localization support.
	 *
	 * @return either an English name of a Provider or an i18n based key that can be scanned for in a properties file.
	 */
	@Override
	String getName() {
		return 'Upcloud Provisioning'
	}

	@Override
	String[] getDiskNameList() {
		return ['vda', 'vdb', 'vdc', 'vdd', 'vde', 'vdf', 'vdg', 'vdh', 'vdi', 'vdj', 'vdk', 'vdl','vdm','vdn','vdo', 'vdp','vdq','vdr','vds','vdt','vdu','vdv','vdw','vdx','vdy','vdz'] as String[]
	}

	static extractDiskDisplayName(name) {
		def rtn = name
		if(rtn) {
			def lastSlash = rtn.lastIndexOf('/')
			if(lastSlash > -1)
				rtn = rtn.substring(lastSlash + 1)
		}
		return rtn
	}

	protected insertVm(Map runConfig, ProvisionResponse provisionResponse, Map opts) {
		log.debug("insertVm runConfig: {}", runConfig)
		def taskResults = [success:false]
		def server = runConfig.server
		def instance = morpheus.async.instance.get(runConfig.instanceId).blockingGet()

		opts.createUserList = runConfig.userConfig.createUsers

		//save server
		// runConfig.server = saveAndGet(server)
		log.debug("create server: ${runConfig}")

		//set install agent
		runConfig.installAgent = runConfig.noAgent && server.cloud.agentMode != 'cloudInit'

		def createResults = UpcloudApiService.createServer(runConfig.authConfig, runConfig)
		log.debug("Upcloud Create Server Results: {}",createResults)
		if(createResults.success == true && createResults.server) {
			server.externalId = createResults.externalId
			server.sshPassword = createResults.server.password ?: runConfig.server.sshPassword
			//server.region = new CloudRegion(code: server.resourcePool.regionCode)
			provisionResponse.externalId = server.externalId
			server = saveAndGet(server)
			runConfig.server = server

			UpcloudApiService.waitForServerExists(runConfig.authConfig, createResults.externalId)
			// wait for ready
			def statusResults = UpcloudApiService.checkServerReady(runConfig.authConfig, createResults.externalId)
			if (statusResults.success == true) {
				//good to go
				def serverDetails = UpcloudApiService.getServerDetail(runConfig.authConfig, createResults.externalId)
				if (serverDetails.success == true) {
					log.debug("server details: {}", serverDetails)

					//update volume info
					setRootVolumeInfo(runConfig.rootVolume, runConfig.platform, serverDetails.volumes)
					setVolumeInfo(runConfig.dataDisks, serverDetails.volumes)
					setNetworkInfo(runConfig.serverInterfaces, serverDetails.networks)
					//update network info
					def privateIp = serverDetails.server.'ip_addresses'?.'ip_address'?.find {
						it.family == 'IPv4' && it.access == 'utility'
					}
					def publicIp = serverDetails.server.'ip_addresses'?.'ip_address'?.find {
						it.family == 'IPv4' && it.access == 'public'
					}
					def serverConfigOpts = [:]
					applyComputeServerNetwork(server, privateIp.address, publicIp.address, null, null, serverConfigOpts)
					taskResults.server = createResults.server
					taskResults.success = true

				} else {
					taskResults.message = 'Failed to get server status'
				}
			} else {
				taskResults.message = 'Failed to create server'
			}
		} else {
			taskResults.message = createResults.msg
		}
		return taskResults

	}

	def finalizeVm(Map runConfig, ProvisionResponse provisionResponse, Map runResults) {
		log.debug("runTask onComplete: provisionResponse: ${provisionResponse}")
		ComputeServer server = context.async.computeServer.get(runConfig.serverId).blockingGet()
		try {
			if(provisionResponse.success == true) {
				server.status = 'provisioned'
				server.statusDate = new Date()
				server.serverType = 'vm'
				server.osDevice = '/dev/vda'
				server.lvmEnabled = server.volumes?.size() > 1
				server.managed = true
				server.capacityInfo = new ComputeCapacityInfo(maxCores:server.plan?.maxCores ?: 1, maxMemory:server.plan?.maxMemory ?: ComputeUtility.ONE_GIGABYTE, maxStorage:runConfig.maxStorage)
				saveAndGet(server)
			}
		} catch(e) {
			log.error("finalizeVm error: ${e}", e)
			provisionResponse.setError('failed to run server: ' + e)
		}
	}

	protected ComputeServer saveAndGet(ComputeServer server) {
		def saveSuccessful = context.async.computeServer.save([server]).blockingGet()
		if(!saveSuccessful) {
			log.warn("Error saving server: ${server?.id}" )
		}
		return context.async.computeServer.get(server.id).blockingGet()
	}

	def setRootVolumeInfo(StorageVolume rootVolume, platform, volumes) {
		if(rootVolume && volumes) {
			def rootDevice = volumes?.find{ it.address?.endsWith(':0')}
			println("setRootVolumeInfo: ${rootDevice}")
			if(!rootDevice)
				rootDevice = volumes?.find{ it.index == 0 }
			println("setRootVolumeInfo: ${rootDevice}")
			if(rootDevice) {
				def deviceName = getDiskName(0, platform)
				def deviceAddress = rootDevice?.address ?: deviceName

				rootVolume.internalId = deviceAddress
				rootVolume.externalId = rootDevice.storageId
				rootVolume.deviceName = deviceName
				rootVolume.deviceDisplayName = extractDiskDisplayName(deviceName)
			}
			context.async.storageVolume.save([rootVolume]).blockingGet()
		}
	}

	def setVolumeInfo(serverVolumes, externalVolumes, doRoot = false) {
		try {
			def maxCount = externalVolumes?.size()
			serverVolumes.sort{it.displayOrder}.eachWithIndex { volume, index ->
				if(index < maxCount && (volume.rootVolume != true || doRoot == true)) {
					if(volume.externalId) {
						log.debug("volume already assigned: ${volume.externalId}")
					} else {
						def volumeMatch = externalVolumes.find{it.index == volume.displayOrder}
						log.debug("looking for volume: ${volume.deviceName} found: ${volumeMatch}")
						if(volumeMatch) {
							def deviceName = volume.deviceName
							def deviceAddress = volumeMatch?.address ?: deviceName
							volume.internalId = deviceAddress
							volume.status = 'provisioned'
							volume.externalId = volumeMatch.storageId
							volume.deviceDisplayName = extractDiskDisplayName(deviceName)
						}
					}
				}
			}
			context.async.storageVolume.save(serverVolumes).blockingGet()
		} catch(e) {
			log.error("setVolumeInfo error: ${e}", e)
		}
	}

	def setNetworkInfo(serverInterfaces, externalNetworks, newInterface = null) {
		try {
			if(externalNetworks?.size() > 0) {
				serverInterfaces?.eachWithIndex { networkInterface, index ->
					if(index == 0) { //only supports 1 interface
						if(networkInterface.externalId) {
							//check for changes?
						} else {
							def privateIp = externalNetworks.find{ it.access == 'utility' && it.family == 'IPv4'}
							def publicIp = externalNetworks.find{ it.access == 'public' && it.family == 'IPv4'}
							def publicIpv6Ip = externalNetworks.find{ it.access == 'public' && it.family == 'IPv6'}
							networkInterface.externalId = "${privateIp.address}"
							if(!networkInterface.publicIpv6Address && publicIpv6Ip)
								networkInterface.publicIpv6Address = publicIpv6Ip.address
							if(networkInterface.type == null)
								networkInterface.type = new ComputeServerInterfaceType(code: privateIp.type ?: 'standard')
							//networkInterface.name = matchNetwork.name
						}
					}
				}
				serverInterfaces?.each { netInt ->
					log.debug("Net Interface: ${netInt.id} -> Network: ${netInt.network?.id}")
				}
				context.async.computeServer.computeServerInterface.save(serverInterfaces).blockingGet()
			}
		} catch(e) {
			log.error("setNetworkInfo error: ${e}", e)
		}
	}

	private applyComputeServerNetwork(server, privateIp, publicIp = null, hostname = null, networkPoolId = null, configOpts = [:], index = 0, networkOpts = [:]) {
		configOpts.each { k,v ->
			server.setConfigProperty(k, v)
		}
		ComputeServerInterface network
		if(privateIp) {
			privateIp = privateIp?.toString().contains("\n") ? privateIp.toString().replace("\n", "") : privateIp.toString()
			def newInterface = false
			server.internalIp = privateIp
			server.sshHost = privateIp
			log.debug("Setting private ip on server:${server.sshHost}")
			network = server.interfaces?.find{it.ipAddress == privateIp}

			if(network == null) {
				if(index == 0)
					network = server.interfaces?.find{it.primaryInterface == true}
				if(network == null)
					network = server.interfaces?.find{it.displayOrder == index}
				if(network == null)
					network = server.interfaces?.size() > index ? server.interfaces[index] : null
			}
			if(network == null) {
				def interfaceName = server.sourceImage?.interfaceName ?: 'eth0'
				network = new ComputeServerInterface(name:interfaceName, ipAddress:privateIp, primaryInterface:true,
						displayOrder:(server.interfaces?.size() ?: 0) + 1, externalId: networkOpts.externalId)
				network.addresses += new NetAddress(type: NetAddress.AddressType.IPV4, address: privateIp)
				newInterface = true
			} else {
				network.ipAddress = privateIp
			}
			if(publicIp) {
				publicIp = publicIp?.toString().contains("\n") ? publicIp.toString().replace("\n", "") : publicIp.toString()
				network.publicIpAddress = publicIp
				server.externalIp = publicIp
			}
			if(networkPoolId) {
				network.poolAssigned = true
				network.networkPool = NetworkPool.get(networkPoolId.toLong())
			}
			if(hostname) {
				server.hostname = hostname
			}

			if(networkOpts) {
				networkOpts.each { key, value ->
					network[key] = value
				}
			}

			if(newInterface == true)
				context.async.computeServer.computeServerInterface.create([network], server).blockingGet()
			else
				context.async.computeServer.computeServerInterface.save([network]).blockingGet()
		}
		saveAndGet(server)
		return network
	}

//	protected buildWorkloadRunConfig(Workload workload, WorkloadRequest workloadRequest, VirtualImage virtualImage, Map opts) {
//		log.debug("buildRunConfig: {}, {}, {}, {}", workload, workloadRequest, virtualImage, opts)
//		Map workloadConfig = workload.getConfigMap()
//		ComputeServer server = workload.server
//		Cloud cloud = server.cloud
//		StorageVolume rootVolume = server.volumes?.find{it.rootVolume == true}
//
//		def maxMemory = server.maxMemory?.div(ComputeUtility.ONE_MEGABYTE)
//		def maxStorage = rootVolume?.getMaxStorage() ?: opts.config?.maxStorage ?: server.plan.maxStorage
//
//		def runConfig = [:] + opts + buildRunConfig(server, virtualImage, workloadRequest.networkConfiguration, workloadConfig, opts)
//
//		runConfig += [
//				name              : server.name,
//				instanceId		  : workload.instance.id,
//				containerId       : workload.id,
//				account 		  : server.account,
//				maxStorage        : maxStorage,
//				maxMemory		  : maxMemory,
//				applianceServerUrl: workloadRequest.cloudConfigOpts?.applianceUrl,
//				workloadConfig    : workload.getConfigMap(),
//				timezone          : (server.getConfigProperty('timezone') ?: cloud.timezone),
//				proxySettings     : workloadRequest.proxyConfiguration,
//				noAgent           : (opts.config?.containsKey("noAgent") == true && opts.config.noAgent == true),
//				installAgent      : (opts.config?.containsKey("noAgent") == false || (opts.config?.containsKey("noAgent") && opts.config.noAgent != true)),
//				userConfig        : workloadRequest.usersConfiguration,
//				cloudConfig	      : workloadRequest.cloudConfigUser,
//				networkConfig	  : workloadRequest.networkConfiguration
//		]
//
//		return runConfig
//
//	}

//	protected buildRunConfig(ComputeServer server, VirtualImage virtualImage, NetworkConfiguration networkConfiguration, config, Map opts) {
//		log.debug("buildRunConfig: {}, {}, {}, {}, {}", server, virtualImage, networkConfiguration, config, opts)
//		def rootVolume = server.volumes?.find{it.rootVolume == true}
//		def dataDisks = server?.volumes?.findAll{it.rootVolume == false}?.sort{it.id}
//		def maxStorage
//		if(rootVolume) {
//			maxStorage = rootVolume.maxStorage
//		} else {
//			maxStorage = config.maxStorage ?: server.plan.maxStorage
//		}
//
//		def runConfig = [
//				serverId: server.id,
//				name: server.name,
//				vpcRef: server.resourcePool?.externalId,
//				zoneRef: server.cloud,
//				server: server,
//				imageType: virtualImage.imageType,
//				osType: (virtualImage.osType?.platform == 'windows' ? 'windows' : 'linux') ?: virtualImage.platform,
//				platform: (virtualImage.osType?.platform == 'windows' ? 'windows' : 'linux') ?: virtualImage.platform,
//				kmsKeyId: config.kmsKeyId,
//				osDiskSize : maxStorage.div(ComputeUtility.ONE_GIGABYTE),
//				maxStorage : maxStorage,
//				osDiskType: rootVolume?.type?.name ?: 'gp2',
//				iops: rootVolume?.maxIOPS,
//				osDiskName:'/dev/sda1',
//				dataDisks: dataDisks,
//				rootVolume:rootVolume,
//				//cachePath: virtualImageService.getLocalCachePath(),
//				virtualImage: virtualImage,
//				hostname: server.getExternalHostname(),
//				hosts: server.getExternalHostname(),
//				diskList:[],
//				domainName: server.getExternalDomain(),
//				securityGroups: config.securityGroups,
//				serverInterfaces:server.interfaces,
//				publicIpType: config.publicIpType ?: 'subnet',
//				fqdn: server.getExternalHostname() + '.' + server.getExternalDomain(),
//		]
//
//		log.debug("Setting snapshot image refs opts.snapshotImageRef: ${opts.snapshotImageRef},  ${opts.rootSnapshotId}")
//		if(opts.snapshotImageRef) {
//			// restore from a snapshot
//			runConfig.imageRef = opts.snapshotImageRef
//			runConfig.osDiskSnapshot = opts.rootSnapshotId
//		} else {
//			// use selected provision image
//			runConfig.imageRef = runConfig.virtualImageLocation.externalId
//			runConfig.osDiskSnapshot = runConfig.virtualImageLocation.externalDiskId
//		}
//
//		return runConfig
//	}

	private void runVirtualMachine(Map runConfig, ProvisionResponse provisionResponse, Map opts) {
		try {
			// don't think this used
			// runConfig.template = runConfig.imageId
			def runResults = insertVm(runConfig, provisionResponse, opts)
			if(provisionResponse.success) {
				finalizeVm(runConfig, provisionResponse, runResults)
			}
		} catch(e) {
			log.error("runVirtualMachine error:${e}", e)
			provisionResponse.setError('failed to upload image file')
		}
	}

	ServiceResponse<ProvisionResponse> runHost(ComputeServer server, HostRequest hostRequest, Map opts) {
		log.debug("runHost: ${server} ${hostRequest} ${opts}")

		ProvisionResponse provisionResponse = new ProvisionResponse()
		try {
			def config = server.getConfigMap()
			Cloud cloud = server.cloud
			Account account = server.account
			ServicePlan plan = server.plan
			def sizeRef = plan.externalId
			config.sizeRef = sizeRef
			def imageType = config.templateTypeSelect ?: 'default'
			def imageId
			def virtualImage
			def layout = server?.layout
			def typeSet = server?.typeSet

			if(layout && typeSet && (typeSet.workloadType.virtualImage || typeSet.workloadType.osType)) {
				Long computeTypeSetId = server.typeSet?.id
				if(computeTypeSetId) {
					ComputeTypeSet computeTypeSet = morpheus.services.computeTypeSet.get(computeTypeSetId)
					WorkloadType workloadType = computeTypeSet.getWorkloadType()
					if(workloadType) {
						Long workloadTypeId = workloadType.id
						WorkloadType containerType = morpheus.services.workloadType.get(workloadTypeId)
						Long virtualImageId = containerType.virtualImage.id
						virtualImage = morpheus.services.virtualImage.get(virtualImageId)
						def imageLocation = virtualImage?.imageLocations.find{it.refId == cloud.id && it.refType == "ComputeZone"}
						imageId = imageLocation?.externalId
					}
				}
			}
			if(!virtualImage && imageType == 'custom' && config.imageId) {
				virtualImage = server.sourceImage
				imageId = virtualImage.externalId
			} else if (!virtualImage) {
				virtualImage  = new VirtualImage(code: 'upcloud.image.morpheus.ubuntu.20.04')
				imageId = virtualImage.externalId
			}

			if(imageId) {
				server.sourceImage = virtualImage
				def rootVolume = server.volumes?.find { it.rootVolume == true }
				def maxStorage = rootVolume.maxStorage
				def dataDisks = server?.volumes?.findAll{it.rootVolume == false}?.sort{it.id}
				server.osDevice = '/dev/vda'
				server.dataDevice = dataDisks?.size() > 0 ? '/dev/vdb' : '/dev/vda'
				opts.server.lvmEnabled = dataDisks?.size() > 0
				opts.createUserList = opts.userConfig.createUsers
				opts.server.sshUsername = opts.userConfig.sshUsername
				opts.server.sshPassword = opts.userConfig.sshPassword
				opts.sshKey  = opts.userConfig.primaryKey
				def createOpts = [
						account		: account,
						name		: server.name,
						maxStorage	: maxStorage,
						imageId		: imageId,
						server		: server,
						zone		: cloud,
						dataDisks	: dataDisks,
						externalId	: server.externalId,
						zoneRef		: server.cloud,
						noAgent		: (opts.config?.containsKey("noAgent") == true && opts.config.noAgent == true),
						installAgent: (opts.config?.containsKey("noAgent") == false || (opts.config?.containsKey("noAgent") && opts.config.noAgent != true)),
						username	: opts.server.sshUsername,
						password	: opts.userConfig.sshPassword,
						imageRef	: imageId,
						sshKey		: opts.sshKey,
						userData	: null,
						rootVolume  : rootVolume
				]
				//cloud init config
				createOpts.hostname = server.getExternalHostname()
				createOpts.cloudConfigUser = hostRequest.cloudConfigUser
				createOpts.cloudConfigMeta = hostRequest.cloudConfigMeta
				createOpts.cloudConfigNetwork = hostRequest.cloudConfigNetwork
				createOpts.networkConfig = hostRequest.networkConfiguration
				createOpts.osType = (virtualImage.osType?.platform == 'windows' ? 'windows' : 'linux') ?: virtualImage.platform
				createOpts.platform = createOpts.osType
				createOpts.userConfig = hostRequest.usersConfiguration

				if(plan.internalId == 'custom') {
					createOpts.maxMemory = server.maxMemory ?: plan.maxMemory
					createOpts.maxCores = server.maxCores ?: plan.maxCores
					createOpts.maxCpu = server.maxCores ?: plan.maxCores
				} else {
					createOpts.planRef = plan.externalId
				}

				if(virtualImage?.isCloudInit) {
					def cloudConfigOpts = hostRequest?.cloudConfigOpts ?: null
					opts.installAgent = (cloudConfigOpts.installAgent != true)

					def cloudConfigUser = hostRequest?.cloudConfigUser ?: null
					createOpts.userData = cloudConfigUser
				}

				context.async.computeServer.save(server).blockingGet()
				//create it
				log.debug("create server: ${createOpts}")
				runVirtualMachine(createOpts, provisionResponse, opts)
			} else {
				server.statusMessage = 'Image not found'
			}
			if (provisionResponse.success != true) {
				return new ServiceResponse(success: false, msg: provisionResponse.message ?: 'vm config error', error: provisionResponse.message, data: provisionResponse)
			} else {
				return new ServiceResponse<ProvisionResponse>(success: true, data: provisionResponse)
			}
		} catch(e) {
			log.error("Error in runHost method: ${e}", e)
			provisionResponse.setError(e.message)
			return new ServiceResponse(success: false, msg: e.message, error: e.message, data: provisionResponse)
		}
	}

	ServiceResponse resizeWorkload(Instance instance, Workload workload, ResizeRequest resizeRequest, Map opts) {
		def server = morpheus.async.computeServer.get(workload.server.id).blockingGet()
		if(server) {
			return internalResizeServer(server, resizeRequest, opts)
		} else {
			return ServiceResponse.error("No server provided")
		}
	}

	ServiceResponse resizeServer(ComputeServer server, ResizeRequest resizeRequest, Map opts = [:]) {
		return internalResizeServer(server, resizeRequest)
	}

	def buildStorageVolume(computeServer, volumeAdd, addDiskResults, newCounter) {
		def newVolume = new StorageVolume(
				refType		: 'ComputeZone',
				refId		: computeServer.cloud.id,
				regionCode	: computeServer.region?.regionCode,
				account		: computeServer.account,
				maxStorage	: volumeAdd.maxStorage?.toLong(),
				maxIOPS		: volumeAdd.maxIOPS?.toInteger(),
				externalId	: addDiskResults.volume?.uuid,
				internalId 	: addDiskResults.volume?.uuid, // This is used in embedded
				deviceName	: addDiskResults.volume?.deviceName,
				name		: volumeAdd.name,
				displayOrder: newCounter,
				status		: 'provisioned',
				unitNumber	: addDiskResults.volume?.deviceIndex?.toString(),
				deviceDisplayName : getDiskDisplayName(newCounter)
		)
		return newVolume
	}

	def internalResizeServer(ComputeServer server, ResizeRequest resizeRequest, Map opts = [:]) {
		ServiceResponse rtn = ServiceResponse.success()
		def authConfigMap = plugin.getAuthConfig(server.cloud)
		ServicePlan plan = resizeRequest.plan

		try {
			server.status = 'resizing'
			server = saveAndGet(server)

			//sizing
			def requestedMemory = resizeRequest.maxMemory
			def requestedCores = resizeRequest?.maxCores
			def currentMemory = server.maxMemory ?: server.getConfigProperty('maxMemory')?.toLong()
			def currentCores = server.maxCores ?: 1
			def neededMemory = requestedMemory - currentMemory
			def neededCores = (requestedCores ?: 1) - (currentCores ?: 1)
			def doStop = (plan?.id != server.plan?.id || neededMemory != 0 || neededCores != 0 || resizeRequest.volumesUpdate)
			if(doStop) {
				def waitResults = UpcloudApiService.waitForServerStatus(authConfigMap, server.externalId, 'stopped')
				if (waitResults.success != true) {
					throw new Exception('error stopping vm ' + (waitResults.msg ?: ''))
				}
			}

			if(neededMemory != 0 || neededCores != 0) {
				//resize it
				def resizeOpts
				if(plan?.id != server.plan?.id)
					resizeOpts = [planRef:plan.externalId]
				else {
					resizeOpts = [maxCores:requestedCores, maxMemory:requestedMemory]
				}
				def resizeResults = UpcloudApiService.resizeServer(authConfigMap, server.externalId, resizeOpts)
				if(resizeResults.success == true) {
					server.plan = plan
					server.maxMemory = requestedMemory.toLong()
					server.maxCores = (requestedCores ?: 1).toLong()
					server.setConfig('maxMemory', plan.maxMemory)
					server = saveAndGet(server)
				} else {
					throw new Exception('error on resize ' + (resizeResults.msg ?: ''))
				}
			}
			//disk sizes
//			def maxStorage = 0
//			maxStorage = resizeRequest.maxStorage
			def newCounter = server.volumes?.size()
			def allStorageVolumeTypes
			if (resizeRequest.volumesUpdate || resizeRequest.volumesAdd) {
				allStorageVolumeTypes = morpheus.async.storageVolume.storageVolumeType.listAll().toMap { it.id }.blockingGet()
			}

			resizeRequest.volumesAdd?.each { newVolumeProps ->
				// new disk add it
				log.debug("Adding New Volume")
				//new disk add it
				if (!newVolumeProps.maxStorage) {
					newVolumeProps.maxStorage = newVolumeProps.size ? (newVolumeProps.size.toDouble() * ComputeUtility.ONE_GIGABYTE).toLong() : 0
				}

				def zoneRef = server.cloud.getConfigMap().zone
				def addDiskConfig = [name: newVolumeProps.name, zoneRef: zoneRef, serverName: server.name, maxStorage: newVolumeProps.volume.maxStorage]
				def addDiskResults = UpcloudApiService.createStorage(authConfigMap, addDiskConfig)
				log.debug("addDiskResults ${addDiskResults}")

				if (!addDiskResults.success)
					throw new Exception("error creating new volume: ${addDiskResults.msg ?: 'unknown'}")
				def newVolumeId = addDiskResults.data.storage.uuid
				def checkReadyResult = UpcloudApiService.checkStorageReady(authConfigMap, newVolumeId)
				if (!checkReadyResult.success)
					throw new Exception("volume never became ready: ${checkReadyResult.msg ?: 'unknown'}")
				// Attach the new one
				def attachResults = UpcloudApiService.attachStorage(authConfigMap, server.externalId, newVolumeId, newCounter)
				if (!attachResults.success)
					throw new Exception("volume failed to attach: ${attachResults.msg ?: 'unknown'}")
				def waitAttachResults = UpcloudApiService.checkStorageReady(authConfigMap, newVolumeId)
				if (!waitAttachResults.success)
					throw new Exception("volume never attached: ${waitAttachResults.msg ?: 'unknown'}")

				def containerServer = morpheus.services.computeServer.get(server.id)
				def volumeType = allStorageVolumeTypes[newVolumeProps.storageType.toLong()] // 'upcloudVolume'
				def newVolume = buildStorageVolume(containerServer.account, containerServer, newVolumeProps.volume, newCounter)
				def deviceName =  getDiskName(newCounter)
				def deviceAddress = attachResults.address ?: deviceName
				newVolume.type = volumeType
				newVolume.maxStorage = newVolumeProps.volume.maxStorage
				newVolume.externalId = newVolumeId
				newVolume.internalId = deviceAddress
				newVolume.deviceName = deviceName
				newVolume.deviceDisplayName = extractDiskDisplayName(deviceAddress)
				log.debug("Saving Volume")
				morpheus.async.storageVolume.create([newVolume], server).blockingGet()
				server = morpheus.async.computeServer.get(server.id).blockingGet()
				newCounter++
			}

			resizeRequest.volumesUpdate?.each { volumeUpdate ->
				StorageVolume existing = volumeUpdate.existingModel
				Map updateProps = volumeUpdate.updateProps
				if (existing) {
					if (updateProps.maxStorage > existing.maxStorage) {
						def volumeId = existing.externalId
						def storageVolumeId = existing.id
						def resizeResults = UpcloudApiService.resizeStorage(authConfigMap, volumeId, [maxStorage: volumeUpdate.volume.maxStorage])
						log.debug("resizeResults ${resizeResults}")

						if (resizeResults.success == true) {
							def existingVolume = context.async.storageVolume.get(existing.id).blockingGet()
							if (existingVolume) {
								existingVolume.maxStorage = updateProps?.maxStorage
								context.async.storageVolume.save(existingVolume).blockingGet()
							} else {
								log.warn("resize volume could not find existing volume to update: ${storageVolumeId}")
							}
						}
					}
				} else {
					// new disk add it
					if (!updateProps.maxStorage) {
						updateProps.maxStorage = updateProps.size ? (updateProps.size.toDouble() * ComputeUtility.ONE_GIGABYTE).toLong() : 0
					}

					def zoneRef = server.cloud.getConfigMap().zone
					def addDiskConfig = [name: updateProps.name, zoneRef: zoneRef, serverName: server.name, maxStorage: updateProps.volume.maxStorage]
					def addDiskResults = UpcloudApiService.createStorage(authConfigMap, addDiskConfig)
					log.debug("addDiskResults ${addDiskResults}")

					if (!addDiskResults.success)
						throw new Exception("error creating new volume: ${addDiskResults.msg ?: 'unknown'}")
					def newVolumeId = addDiskResults.data.storage.uuid
					def checkReadyResult = UpcloudApiService.checkStorageReady(authConfigMap, newVolumeId)
					if (!checkReadyResult.success)
						throw new Exception("volume never became ready: ${checkReadyResult.msg ?: 'unknown'}")
					// Attach the new one
					def attachResults = UpcloudApiService.attachStorage(authConfigMap, server.externalId, newVolumeId, newCounter)
					if (!attachResults.success)
						throw new Exception("volume failed to attach: ${attachResults.msg ?: 'unknown'}")
					def waitAttachResults = UpcloudApiService.checkStorageReady(authConfigMap, newVolumeId)
					if (!waitAttachResults.success)
						throw new Exception("volume never attached: ${waitAttachResults.msg ?: 'unknown'}")

					def containerServer = morpheus.services.computeServer.get(server.id)
					def volumeType = allStorageVolumeTypes[updateProps.storageType.toLong()] // 'upcloudVolume'
					def newVolume = buildStorageVolume(containerServer.account, containerServer, updateProps.volume, newCounter)
					def deviceName = getDiskName(newCounter)
					def deviceAddress = attachResults.address ?: deviceName
					newVolume.type = volumeType
					newVolume.maxStorage = updateProps.volume.maxStorage
					newVolume.externalId = newVolumeId
					newVolume.internalId = deviceAddress
					newVolume.deviceName = deviceName
					newVolume.deviceDisplayName = extractDiskDisplayName(deviceAddress)
					log.debug("Saving Volume")
					morpheus.async.storageVolume.create([newVolume], server).blockingGet()
					server = morpheus.async.computeServer.get(server.id).blockingGet()
					newCounter++
				}
			}

			resizeRequest.volumesDelete.each { volume ->
				log.debug("deleting volume : ${volume.externalId}")
				def volumeId = volume.externalId
				def volumeAddress = volume.internalId
				if(volumeAddress) {
					def detachResults = UpcloudApiService.detachStorage(authConfigMap, server.externalId, volumeAddress)
					if(detachResults.success == true) {
						UpcloudApiService.removeStorage(authConfigMap, volumeId)
						morpheus.async.storageVolume.remove([volume], server, true).blockingGet()
					}
				}
			}
			server.status = 'provisioned'
			server = saveAndGet(server)

			if(doStop == true) {
				def waitResults = UpcloudApiService.waitForServerStatus(authConfigMap, server.externalId, 'running')
			}

			rtn.success = true
		} catch(ex) {
			log.error("Error resizing instance to ${plan.name}", ex)
			rtn.success = false
			rtn.msg = "Error resizing instance to ${plan.name} ${ex.getMessage()}"
			rtn.error= "Error resizing instance to ${plan.name} ${ex.getMessage()}"
		}
		return rtn
	}
}
