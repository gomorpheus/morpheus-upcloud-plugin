package com.morpheusdata.upcloud.sync

import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.projection.ServicePlanIdentityProjection
import com.morpheusdata.upcloud.util.UpcloudComputeUtility

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataAndFilter
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.Workload
import com.morpheusdata.model.Instance
import com.morpheusdata.model.ComputeCapacityInfo
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.upcloud.UpcloudPlugin
import com.morpheusdata.upcloud.services.UpcloudApiService
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.core.util.SyncList
import com.morpheusdata.upcloud.UpcloudProvisionProvider
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

@Slf4j
class VirtualMachinesSync {
    private Cloud cloud
    UpcloudPlugin plugin
    private MorpheusContext morpheusContext

    VirtualMachinesSync(Cloud cloud, UpcloudPlugin plugin, MorpheusContext morpheusContext) {
        this.cloud = cloud
        this.plugin = plugin
        this.morpheusContext = morpheusContext
    }

    def execute() {
        try {
            def authConfig = plugin.getAuthConfig(cloud)
            def apiResults = UpcloudApiService.listServers(authConfig)
            log.info("apiResults: ${apiResults}")

            if (apiResults.success == true) {
                def servicePlans = morpheusContext.async.servicePlan.listIdentityProjections(
                        new DataQuery().withFilter("provisionType", "upcloud")
                        .withFilter("active", true)
                )

                def serverRecords = morpheusContext.async.computeServer.listIdentityProjections(
                        new DataQuery().withFilter("account", cloud.account)
                        .withFilter("zone_id", cloud.id)
                )
                log.info("SERVER RECORDS: ${serverRecords}")

                SyncTask<ComputeServerIdentityProjection, Map, ComputeServer> syncTask = new SyncTask<>(serverRecords, apiResults.data.servers.server as Collection<Map>) as SyncTask<ComputeServerIdentityProjection, Map, ComputeServer>
                syncTask.addMatchFunction { ComputeServerIdentityProjection imageObject, Map cloudItem ->
                    imageObject.externalId == cloudItem?.uuid
                }.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItems ->
                    morpheusContext.async.computeServer.listById(updateItems.collect { it.existingItem.id } as List<Long>)
                }.onAdd { itemsToAdd ->
                    addMissingVirtualMachines(itemsToAdd, servicePlans)
                }.onUpdate { List<SyncTask.UpdateItem<ComputeServer, Map>> updateItems ->
                    updateMatchedVirtualMachines(updateItems)
                }.onDelete { removeItems ->
                    removeMissingVirtualMachines(removeItems)
                }.start()
            } else {
                log.error "Error in getting images: ${apiResults}"
            }
        } catch(e) {
            log.error("cacheVirtualMachines error: ${e}", e)
        }
    }

    private addMissingVirtualMachines(Collection<Map> addList, Observable<ServicePlanIdentityProjection> servicePlans) {
        def authConfig = plugin.getAuthConfig(cloud)
        def serverType = new ComputeServerType(code: 'upcloudUnmanaged')
        def adds = []
        def serverAdds = [:]

        try {
            for(cloudItem in addList) {
                def addConfig = [account:cloud.account, externalId:cloudItem.uuid, name:cloudItem.title, sshUsername:'root',
                     apiKey:java.util.UUID.randomUUID(), status:'provisioned', hostname:cloudItem.hostname, poweredOn:(cloudItem.state == 'started'),
                     powerState:(cloudItem.state == 'started' ? 'on' : 'off'), computeServerType:serverType, provision:false,
                     singleTenant:true, zone:cloud, lvmEnabled:false, managed:false, discovered:true, serverType:'unmanaged'
                ]
                def addCapacityConfig = [maxCores:(cloudItem.'core_number' ?: 1), maxMemory:(cloudItem['memory_amount']?.toLong()*ComputeUtility.ONE_MEGABYTE),
                     maxStorage:0, usedStorage:0
                ]
                def serverResults = UpcloudComputeUtility.getServerDetail(authConfig, cloudItem.uuid)

                if(serverResults.success == true && serverResults.server) {
                    //stats and ip address info
                    if(serverResults.server.vnc == 'on') {
                        addConfig.consoleType = 'vnc'
                        addConfig.consoleHost = cloudItem.'vnc_host'
                        addConfig.consolePassword = cloudItem.'vnc_password'
                        addConfig.consolePort = cloudItem.'vnc_port'
                    }
                    //ip addresses
                    if(serverResults.networks) {
                        def publicIp = serverResults.networks.find{ nic -> nic.family == 'IPv4' && nic.access == 'public' }
                        def privateIp = serverResults.networks.find{ nic -> nic.family == 'IPv4' && nic.access == 'utility' }
                        if(privateIp)
                            addConfig.internalIp = privateIp.address
                        if(publicIp)
                            addConfig.externalIp = publicIp.address
                        addConfig.sshHost = addConfig.externalIp ?: addConfig.internalIp
                    }
                    //volumes
                    if(serverResults.volumes) {
                        def maxStorage = serverResults.volumes.sum{ volume -> (volume.size ?: 0) }
                        addCapacityConfig.maxStorage = maxStorage * ComputeUtility.ONE_GIGABYTE
                    }
                    //plan
                    ServicePlan servicePlan = findServicePlanMatch(servicePlans, serverResults.server)
                    if(servicePlan) {
                        addConfig.plan = servicePlan
                    }
                }

                def add = new ComputeServer(addConfig)
                addCapacityConfig.server = add
                add.capacityInfo = new ComputeCapacityInfo()(addCapacityConfig)
                adds << add
                serverAdds[add.id] = serverResults.volumes
            }

            morpheusContext.async.computeServer.bulkCreate(adds).blockingGet()
            adds?.each {
                cacheVirtualMachineVolumes(cloud, it, serverAdds[it.id])
            }
        } catch(e2) {
            log.error("error creating new unmanaged upcloud server during sync operation: ${e2}", e2)
        }
    }

    private updateMatchedVirtualMachines(List<SyncTask.UpdateItem<ComputeServer, Map>> updateList) {
        def saves = []
        def savesVolumes = [:]
        def savesCloudServers = [:]
        def authConfig = plugin.getAuthConfig(cloud)
        def serverType = new ComputeServerType(code:'upcloudUnmanaged')
        def servicePlans = morpheusContext.async.servicePlan.listIdentityProjections(
                new DataQuery().withFilter("provisionType", "upcloud")
                        .withFilter("active", true)
        )

        try {
            for(updateItem in updateList) {
                def server
                def vm = updateItem.masterItem
                server = updateItem.existingItem
                if (server && server.status != 'provisioning') {
                    def cloudServer = updateItem.masterItem
                    def powerState = cloudServer.state == 'started' ? 'on' : 'off'
                    def maxCores = cloudServer.'core_number'?.toLong() ?: 1
                    def maxMemory = cloudServer.'memory_amount'?.toLong() * ComputeUtility.ONE_MEGABYTE
                    def serverResults
                    def doSave = false
                    if (server.computeServerType == null) {
                        server.computeServerType = serverType
                        doSave = true
                    }
                    if (cloudServer.title != server.name) {
                        server.name = cloudServer.title
                        doSave = true
                    }
                    if (server.maxMemory != maxMemory) {
                        server.maxMemory = maxMemory
                        doSave = true
                    }
                    if (server.maxCores != maxCores) {
                        server.maxCores = maxCores
                        doSave = true
                    }
                    if (server.capacityInfo.maxMemory != maxMemory) {
                        server.capacityInfo.maxMemory = maxMemory
                        doSave = true
                    }
                    serverResults = UpcloudComputeUtility.getServerDetail(authConfig, vm.uuid)
                    if (serverResults.success == true && serverResults.server) {
                        //stats and ip address info
                        def vncOn = serverResults.server.vnc == 'on'
                        log.debug("Server Result: ${serverResults}")
                        if (vncOn == false && server.consoleType != null) {
                            server.consoleType = null
                            server.consoleHost = null
                            server.consolePassword = null
                            server.consolePort = null
                        } else {
                            if (server.consoleType != 'vnc') {
                                server.consoleType = 'vnc'
                                doSave = true
                            }
                            if (server.consoleHost != serverResults.server.'vnc_host') {
                                server.consoleHost = serverResults.server.'vnc_host'
                                doSave = true
                            }
                            if (server.consolePassword != serverResults.server.'vnc_password') {
                                server.consolePassword = serverResults.server.'vnc_password'
                                doSave = true
                            }
                            if (server.consolePort != serverResults.server.'vnc_port') {
                                server.consolePort = serverResults.server.'vnc_port'
                                doSave = true
                            }
                        }
                        //ip addresses
                        if (serverResults.networks) {
                            def publicIp = serverResults.networks.find { nic -> nic.family == 'IPv4' && nic.access == 'public' }
                            def privateIp = serverResults.networks.find { nic -> nic.family == 'IPv4' && nic.access == 'utility' }
                            if (privateIp && server.internalIp != privateIp.address) {
                                server.internalIp = privateIp.address
                                doSave = true
                            }
                            if (publicIp && server.externalIp != publicIp?.address) {
                                server.externalIp = publicIp.address
                                doSave = true
                            }
                            if (publicIp && server.sshHost != publicIp?.address) {
                                server.sshHost = publicIp.address
                                doSave = true
                            }
                            if (!publicIp && server.sshHost != privateIp?.address) {
                                server.sshHost = privateIp.address
                                doSave = true
                            }
                        }
                        //volumes
                        if (serverResults.volumes) {
                            def maxStorage = serverResults.volumes.sum { volume -> (volume.size ? (volume.size.toLong() * ComputeUtility.ONE_GIGABYTE) : 0) }
                            if (server.maxStorage != maxStorage) {
                                server.maxStorage = maxStorage
                                doSave = true
                            }
                            if (server.capacityInfo.maxStorage != maxStorage) {
                                server.capacityInfo.maxStorage = maxStorage
                                doSave = true
                            }
                            savesVolumes[server.id] = server.volume
                        }
                    }
                    if (powerState != server.powerState) {
                        server.powerState = powerState
                        doSave = true
                    }
                    //Set the plan on the server
                    if (!server.plan) {
                        ServicePlan servicePlan = findServicePlanMatch(servicePlans, vm)
                        if (servicePlan) {
                            server.plan = servicePlan
                            if (server.plan?.internalId == 'custom') {
                                server.maxMemory = maxMemory
                                server.maxCores = maxCores
                            } else {
                                server.maxMemory = servicePlan.maxMemory
                                server.maxCores = servicePlan.maxCores
                            }
                            doSave = true
                        }
                    }
                    if (doSave == true) {
                        saves << server
                        savesCloudServers[cloudServer.id] = cloudServer.server
                    }
                }
            }
            morpheusContext.async.computeServer.bulkSave(saves).blockingGet()
            saves?.each { it ->
                cacheVirtualMachineVolumes(it, savesCloudServers[it.id], savesVolumes[it.id])
            }
        } catch(e) {
            log.warn("error syncing existing vm ${server.id}: ${e}", e)
        }
    }

    def removeMissingVirtualMachines(List removeList) {
        log.debug("VIRTUAL MACHINES REMOVE LIST: ${removeList}")
        morpheusContext.async.computeServer.bulkRemove(removeList).blockingGet()
    }

    private findServicePlanMatch(servicePlans, vm) {
        def rtn

        if(vm.'plan' == 'custom') {
            rtn = servicePlans?.find { it.internalId == 'custom' }
        } else {
            rtn = servicePlans?.find { it.externalId == vm.'plan' }
        }

        return rtn
    }

    private cacheVirtualMachineVolumes(ComputeServer server, List volumes) {
        def saveRequired = false
        try {
            //ignore servers that are being resized
            if(server.status == 'resizing') {
                log.warn("ignoring server ${server} because it is resizing")
                return saveRequired
            }
            def storageType = new StorageVolumeType(code:'upcloudVolume')
            SyncList.MatchFunction matchFunction = { StorageVolume morpheusVolume, Map volume ->
                morpheusVolume?.externalId == volume.storageId
            }
            def syncLists = new SyncList(matchFunction).buildSyncLists(server.volumes, volumes)

            def removeList = []
            def saveList = []
            def createList = []

            //adds
            syncLists.addList?.each { Map volume ->
                log.debug("adding volume: ${volume}")
                def volumeId = volume.storageId
                def addVolume = new StorageVolume([maxStorage:volume.size * ComputeUtility.ONE_GIGABYTE, type:storageType,
                                                   externalId:volumeId, deviceName:volume.deviceName, name:volume.name, cloudId:server.cloud?.id])
                def volumeName = UpcloudProvisionProvider.getDiskName(volume.diskIndex)
                addVolume.deviceDisplayName = UpcloudProvisionProvider.extractDiskDisplayName(volumeName)
                if(volumeName == '/dev/vda')
                    addVolume.rootVolume = true
                createList << addVolume
                saveRequired = true
            }
            //updates
            syncLists.updateList?.each { updateMap ->
                log.debug("processing update item: ${updateMap}")
                def existingVolume = updateMap.existingItem
                def cloudVolume = updateMap.masterItem
                def volumeId = existingVolume.externalId
                def save = false
                if(existingVolume.maxStorage != cloudVolume.size * ComputeUtility.ONE_GIGABYTE) {
                    existingVolume.maxStorage = cloudVolume.size * ComputeUtility.ONE_GIGABYTE
                    save = true
                }
                def volumeName = UpcloudProvisionProvider.getDiskName(cloudVolume.diskIndex)
                def deviceDisplayName = UpcloudProvisionProvider.extractDiskDisplayName(volumeName)
                if(deviceDisplayName != existingVolume.deviceDisplayName) {
                    existingVolume.deviceDisplayName = deviceDisplayName
                    save = true
                }
                def rootVolume = volumeName == '/dev/vda'
                if(rootVolume != existingVolume.rootVolume) {
                    existingVolume.rootVolume = rootVolume
                    save = true
                }
                if(save) {
                    saveList << existingVolume
                }
            }
            // Process removes
            syncLists.removeList?.each { StorageVolume existingVolume ->
                log.debug("removing volume ${existingVolume}")
                server.volumes.remove(existingVolume)
                removeList << existingVolume
                saveRequired = true
            }

            if(createList) {
                morpheusContext.async.storageVolume.create(createList, server).blockingGet()
            }

            if(saveList) {
                morpheusContext.async.storageVolume.bulkSave(saveList).blockingGet()
            }

            if(removeList) {
                morpheusContext.async.storageVolume.remove(removeList, server, false).blockingGet()
            }
        } catch(e) {
            log.error("error syncing volumes ${e}", e)
        }
        return saveRequired
    }
}

