package com.morpheusdata.upcloud.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.InstanceType
import com.morpheusdata.model.InstanceTypeLayout
import com.morpheusdata.model.OsType
import com.morpheusdata.model.ProvisionType
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.WorkloadType
import com.morpheusdata.model.WorkloadTypeSet
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.model.projection.VirtualImageIdentityProjection
import com.morpheusdata.upcloud.UpcloudPlugin
import com.morpheusdata.upcloud.services.UpcloudApiService
import groovy.util.logging.Slf4j

@Slf4j
class PublicTemplatesSync {

    private Cloud cloud
    UpcloudPlugin plugin
    private MorpheusContext morpheusContext

    PublicTemplatesSync(Cloud cloud, UpcloudPlugin plugin, MorpheusContext morpheusContext) {
        this.cloud = cloud
        this.plugin = plugin
        this.morpheusContext = morpheusContext
    }

    def execute() {
        try {
            def authConfig = plugin.getAuthConfig(cloud)
            def imageResults = UpcloudApiService.listPublicTemplates(authConfig)
            log.debug("imageResults: ${imageResults}")

            if (imageResults.success == true) {
                def imageRecords = morpheusContext.async.virtualImage.listIdentityProjections(
                        new DataQuery().withFilter("category", "upcloud.image.public.template")
                )
                SyncTask<VirtualImageIdentityProjection, Map, VirtualImage> syncTask = new SyncTask<>(imageRecords, imageResults.data.storages.storage as Collection<Map>) as SyncTask<VirtualImageIdentityProjection, Map, VirtualImage>
                syncTask.addMatchFunction { VirtualImageIdentityProjection imageObject, Map cloudItem ->
                    imageObject.externalId == cloudItem?.uuid
                }.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<VirtualImageIdentityProjection, Map>> updateItems ->
                    morpheusContext.async.virtualImage.listById(updateItems.collect { it.existingItem.id } as List<Long>)
                }.onAdd { itemsToAdd ->
                    addMissingImages(itemsToAdd)
                }.onUpdate { List<SyncTask.UpdateItem<VirtualImage, Map>> updateItems ->
                    updateMatchedImages(updateItems)
                }.onDelete { removeItems ->
                    removeMissingImages(removeItems)
                }.start()
            } else {
                log.error "Error in getting images: ${imageResults}"
            }
        } catch(e) {
            log.error("cachePublicTemplates error: ${e}", e)
        }
    }

    private addMissingImages(Collection<Map> addList) {
        def saves = []
        try {
            for (cloudItem in addList) {
                def imageConfig =
                    [
                        category: "upcloud.image.public.template",
                        owner: cloud.owner,
                        name: cloudItem.title,
                        //zoneType: 'upcloud',
                        code: "upcloud.image.public.template.${cloudItem.uuid}",
                        isCloudInit: false,
                        imageType: 'qcow2',
                        uniqueId: cloudItem.uuid,
                        externalId: cloudItem.uuid,
                        isPublic: cloudItem.visibility,
                        platform: cloudItem.title.startsWith("Windows ") ? 'windows' : 'linux',
                        minDisk: cloudItem.size
                    ]

                imageConfig.osType = new OsType(code:(cloudItem.title.startsWith("Windows ") ? 'windows' : 'linux'))
                if(imageConfig.platform == 'windows') {
                    imageConfig.sshUsername = 'clouduser'
                }

                def newImage = new VirtualImage(imageConfig)
                saves << newImage

                def layoutMatch = imageTypeMap.find{ imageType -> imageType.match == cloudItem.title}
                if(layoutMatch?.map == true) {
                    createTemplateLayout(newImage, layoutMatch, cloudItem)
                }
            }
            morpheusContext.async.virtualImage.bulkCreate(saves).blockingGet()
        } catch(e) {
            log.error("addVirtualImage error: ${e}", e)
        }
    }

    private updateMatchedImages(List<SyncTask.UpdateItem<VirtualImage, Map>> updateList) {
        def saves = []
        def zoneCategory = "upcloud.layout.public.template"

        try {
            for(updateItem in updateList) {
                def template = updateItem.masterItem
                def existingItem = updateItem.existingItem
                def layoutMatch = imageTypeMap.find{ imageType -> imageType.match == template.title }
                log.debug("TEMPLATE NAME: ${template.title}")
                log.debug("LAYOUT MATCH 113: ${layoutMatch}, ${layoutMatch?.map}")
                log.debug("CODE: ${zoneCategory}.${layoutMatch?.instanceType}")
                log.debug("VERSION: ${layoutMatch?.version}")
                if(layoutMatch?.map == true) {
                    log.debug("Matching up : ${template.title}")
                    def layout = morpheusContext.async.instanceTypeLayout.find(
                            new DataQuery().withFilter("code", "=", "${zoneCategory}.${layoutMatch.instanceType}")
                                    .withFilter("instanceVersion", "=", layoutMatch.version)
                    ).blockingGet()
                    if(layout) {
                        def workloadType = morpheusContext.async.workloadType.find(
                                new DataQuery().withFilter("code", "=", "${zoneCategory}.${layoutMatch.instanceType}")
                                        .withFilter("containerVersion", "=", layoutMatch.version)
                        ).blockingGet()
                        def save = false
                        if(!existingItem.osType) {
                            existingItem.osType = new OsType(code:template.title.startsWith("Windows ") ? 'windows' : 'linux')
                            save = true
                        }
                        if(existingItem.platform == 'windows' && !existingItem.sshUsername) {
                            existingItem.sshUsername = 'clouduser'
                        }
                        if(workloadType) {
                            if(workloadType.commEnabled != layoutMatch.commEnabled) {
                                workloadType.commEnabled = layoutMatch.commEnabled
                                workloadType.commType = layoutMatch.commType
                                workloadType.commPort = layoutMatch.commPort
                                save = true
                            }

                            if(save) {
                                saves << existingItem
                                saves << workloadType
                            }
                        }
                        if(!layout.supportsConvertToManaged) {
                            layout.supportsConvertToManaged = true
                            saves << layout
                        }
                        if(layout.sortOrder != layoutMatch.sortOrder) {
                            layout.sortOrder = layoutMatch.sortOrder
                            saves << layout
                        }
                    } else {
                        createTemplateLayout(existingItem, layoutMatch, template)
                    }
                } else {
                    def layout
                    if(layoutMatch) {
                        layout = morpheusContext.async.instanceTypeLayout.find(
                                new DataQuery().withFilter("code", "=", "${zoneCategory}.${layoutMatch.instanceType}")
                                        .withFilter("instanceVersion", "=", layoutMatch.version)
                                        .withFilter("enabled", "=", true)
                        ).blockingGet()
                    }

                    if(layout) {
                        layout.enabled = false
                        saves << layout
                    }
                }
            }
            if(saves) {
                morpheusContext.async.instanceTypeLayout.bulkSave(saves).blockingGet()
            }
        } catch(e) {
            log.error("updateMatchedImages error: ${e}", e)
        }
    }

    def removeMissingImages(List removeList) {

        def typeDeletes = []
        def setDeletes = []
        def layoutDeletes = []
        def imageDeletes = []

        removeList?.each { VirtualImageIdentityProjection morpheusItem ->
            VirtualImage virtualImage = morpheusContext.async.virtualImage.get(morpheusItem.id).blockingGet()
            def workloadTypes = morpheusContext.services.workloadType.list(
                    new DataQuery().withFilter("virtualImage.id", "=", virtualImage.id)
            )
            workloadTypes?.each {
                it.virtualImage = null
            }
            morpheusContext.services.workloadType.bulkSave(workloadTypes)
            workloadTypes?.findAll{it.code.startsWith("upcloud.layout.public.template")}?.toArray().each { ctype ->
                morpheusContext.async.workload.typeSet.list(
                    new DataQuery().withFilter("workloadType.id", "=", ctype.id)
                ).toList().each{ WorkloadTypeSet cset ->
                    morpheusContext.services.instanceTypeLayout.list(
                        new DataQuery().withFilter("workloads.id", "=", cset.id)
                    ).toList().each{layout ->
                        morpheusContext.services.instance.list(
                                new DataQuery().withFilter("layout.id", "=", layout.id)
                        ).toList().each {instance ->
                                instance.layout = null
                                morpheusContext.async.instance.save(instance).blockingGet()
                        }
                        layoutDeletes << layout
                    }
                    setDeletes << cset
                }
                typeDeletes << ctype
            }
            imageDeletes << virtualImage
        }

        morpheusContext.async.instanceTypeLayout.bulkRemove(layoutDeletes).blockingGet()
        morpheusContext.async.workload.typeSet.bulkRemove(setDeletes).blockingGet()
        morpheusContext.async.workloadType.bulkRemove(typeDeletes).blockingGet()
        morpheusContext.async.virtualImage.bulkRemove(imageDeletes).blockingGet()
    }


    def createTemplateLayout(VirtualImage image, Map typeMatch, Map imageConfig) {
        def zoneCategory = "upcloud.layout.public.template"
        def instanceType = new InstanceType(code:typeMatch.instanceType)

        //container type
        def provisionType = new ProvisionType(code:'upcloud')
        def workloadTypeConfig = [code:zoneCategory + '.' + typeMatch.instanceType, shortName:typeMatch.instanceType,
                                   name:'UpCloud ' + typeMatch.name, containerVersion:typeMatch.version, repositoryImage:'image', entryPoint:'/bin/bash',
                                   statTypeCode:typeMatch.statType, logTypeCode:typeMatch.logType, virtualImage:image,
                                   checkTypeCode:typeMatch.checkType, commEnabled:typeMatch.commEnabled, commType:typeMatch.commType, commPort:typeMatch.commPort,
                                   category:typeMatch.instanceType, provisionType:provisionType, syncSource:'external',
                                   uuid: java.util.UUID.randomUUID(), hasSettings: false, userDeploy: true, showServerLogs: false]
        def workloadType = new WorkloadType(workloadTypeConfig)
        morpheusContext.async.workloadType.create(workloadType).blockingGet()

        //type set
        def workloadTypeSetConfig = [code:zoneCategory + '.' + typeMatch.instanceType + '.set',
                                      workloadType:workloadType, priorityOrder:0, containerCount:1,
                                      dynamicCount: false]
        def workloadTypeSet = new WorkloadTypeSet(workloadTypeSetConfig)
        morpheusContext.async.workload.typeSet.create(workloadTypeSet).blockingGet()

        //layout
        def layoutConfig = [code:zoneCategory + '.' + typeMatch.instanceType,
                            name:'UpCloud ' + typeMatch.name, externalId:image.externalId,
                            sortOrder:typeMatch.sortOrder, instanceVersion:typeMatch.version, description:'Provision upcloud ' + typeMatch.instanceType + ' vm',
                            provisionType:provisionType, instanceType:instanceType, serverCount:1, portCount:workloadType.ports?.size() ?: 0,
                            serverType:'vm', supportsConvertToManaged:true, syncSource:'external',
                            creatable: true, uuid: java.util.UUID.randomUUID(), systemLayout: false, enabled: true]
        def layout = new InstanceTypeLayout(layoutConfig)
        layout.workloads = layout.workloads ? layout.workloads.add(workloadTypeSet) : [workloadTypeSet]
        //layout.workloads.add(workloadTypeSet)
        //instanceTypeService.setInstanceTypeLayoutToScale(layout)
        morpheusContext.async.instanceTypeLayout.create(layout).blockingGet()
    }


    static imageTypeMap = [

            [match:'CentOS Stream 9', name:'CentOS Stream 9', instanceType:'centos', sortOrder:99, version:'9-stream', os:'linux', map:true,
             checkType:'vmCheck', statType:'centos', logType:'centos', commType: 'SSH', commPort: 22, commEnabled: true],
            [match:'CentOS Stream 8', name:'CentOS Stream 8', instanceType:'centos', sortOrder:89, version:'8-stream', os:'linux', map:true,
             checkType:'vmCheck', statType:'centos', logType:'centos', commType: 'SSH', commPort: 22, commEnabled: true],
            [match:'CentOS 7', name:'CentOS 7', instanceType:'centos', version:'7.9', sortOrder:79, os:'linux', map:true,
             checkType:'vmCheck', statType:'centos', logType:'centos', commType: 'SSH', commPort: 22, commEnabled: true],

            [match:'Ubuntu Server 16.04 LTS (Xenial Xerus)', name:'Ubuntu 16.04',sortOrder: 16, instanceType:'ubuntu', version:'16.04', os:'linux', map:true,
             checkType:'vmCheck', statType:'ubuntu', logType:'ubuntu', commType: 'SSH', commPort: 22, commEnabled: true],
            [match:'Ubuntu Server 18.04 LTS (Bionic Beaver)', name:'Ubuntu 18.04',sortOrder: 18, instanceType:'ubuntu', version:'18.04', os:'linux', map:true,
             checkType:'vmCheck', statType:'ubuntu', logType:'ubuntu', commType: 'SSH', commPort: 22, commEnabled: true],
            [match:'Ubuntu Server 20.04 LTS (Focal Fossa)', name:'Ubuntu 20.04',sortOrder: 20, instanceType:'ubuntu', version:'20.04', os:'linux', map:true,
             checkType:'vmCheck', statType:'ubuntu', logType:'ubuntu', commType: 'SSH', commPort: 22, commEnabled: true],
            [match:'Ubuntu Server 22.04 LTS (Jammy Jellyfish)', name:'Ubuntu 22.04',sortOrder: 22, instanceType:'ubuntu', version:'22.04', os:'linux', map:true,
             checkType:'vmCheck', statType:'ubuntu', logType:'ubuntu', commType: 'SSH', commPort: 22, commEnabled: true],
            [match:'Ubuntu Server 14.04 LTS (Trusty Tahr)', name:'Ubuntu 14.04',sortOrder: 14, instanceType:'ubuntu', version:'14.04', os:'linux', map:true,
             checkType:'vmCheck', statType:'ubuntu', logType:'ubuntu', commType: 'SSH', commPort: 22, commEnabled: true],
            [match:'Ubuntu Server 24.04 LTS (Noble Numbat)', name:'Ubuntu 24.04',sortOrder: 24, instanceType:'ubuntu', version:'24.04', os:'linux', map:true,
             checkType:'vmCheck', statType:'ubuntu', logType:'ubuntu', commType: 'SSH', commPort: 22, commEnabled: true],

            [match:'Debian GNU/Linux 12 (Bookworm)', name:'Debian 12', sortOrder: 12, instanceType:'debian', version:'12', os:'linux', map:true,
             checkType:'vmCheck', statType:'debian', logType:'debian', commType: 'SSH', commPort: 22, commEnabled: true],
            [match:'Debian GNU/Linux 11 (Bullseye)', name:'Debian 11', sortOrder: 11, instanceType:'debian', version:'11', os:'linux', map:true,
             checkType:'vmCheck', statType:'debian', logType:'debian', commType: 'SSH', commPort: 22, commEnabled: true],
            [match:'Debian GNU/Linux 10 (Buster)', name:'Debian 10', sortOrder:10, instanceType:'debian', version:'10.10', os:'linux', map:true,
             checkType:'vmCheck', statType:'debian', logType:'debian', commType: 'SSH', commPort: 22, commEnabled: true],
            [match:'Debian GNU/Linux 9 (Stretch)', name:'Debian 9', sortOrder:9, instanceType:'debian', version:'9.13', os:'linux', map:true,
             checkType:'vmCheck', statType:'debian', logType:'debian', commType: 'SSH', commPort: 22, commEnabled: true],
            [match:'Debian GNU/Linux 8.7 (Jessie)', name:'Debian 8', sortOrder:8, instanceType:'debian', version:'8.8', os:'linux', map:false,
             checkType:'vmCheck', statType:'debian', logType:'debian', commType: 'SSH', commPort: 22, commEnabled: true],
            [match:'Debian GNU/Linux 7.8 (Wheezy)', name:'Debian 7', sortOrder:7, instanceType:'debian', version:'', os:'linux', map:false,
             checkType:'vmCheck', statType:'debian', logType:'debian', commType: 'SSH', commPort: 22, commEnabled: true],

            [match:'Windows Server 2016 Standard', name:'Windows Server 2016', sortOrder:2016, instanceType:'windows', version:'2016', os:'windows', map:false,
             checkType:'vmCheck', statType:'windows', logType:'windows', commType: 'RDP', commEnabled: true, commPort: 3389],
            [match:'Windows Server 2019 Datacenter', name:'Windows Server 2019 Datacenter', sortOrder:2019, instanceType:'windows', version:'2019 datacenter', os:'windows', map:false,
             checkType:'vmCheck', statType:'windows', logType:'windows', commType: 'RDP', commEnabled: true, commPort: 3389],
            [match:'Windows Server 2019 Standard', name:'Windows Server 2019', sortOrder:2019, instanceType:'windows', version:'2019', os:'windows', map:false,
             checkType:'vmCheck', statType:'windows', logType:'windows', commType: 'RDP', commEnabled: true, commPort: 3389],
            [match:'Windows Server 2016 Datacenter', name:'Windows Server 2016 Datacenter', sortOrder:2016, instanceType:'windows', version:'2016 datacenter', os:'windows', map:false,
             checkType:'vmCheck', statType:'windows', logType:'windows', commType: 'RDP', commEnabled: true, commPort: 3389],
            [match:'Windows Server 2022 Datacenter', name:'Windows Server 2022 Datacenter', sortOrder:2022, instanceType:'windows', version:'2022 datacenter', os:'windows', map:false,
             checkType:'vmCheck', statType:'windows', logType:'windows', commType: 'RDP', commEnabled: true, commPort: 3389],
            [match:'Windows Server 2022 Standard', name:'Windows Server 2022', sortOrder:2022, instanceType:'windows', version:'2022', os:'windows', map:false,
             checkType:'vmCheck', statType:'windows', logType:'windows', commType: 'RDP', commEnabled: true, commPort: 3389],

            [match:'AlmaLinux 8', name:'AlmaLinux 8', sortOrder:8, instanceType:'almalinux', version:'8', os:'linux', map:true,
             checkType:'vmCheck', statType:'centos', logType:'centos', commType: 'SSH', commPort: 22, commEnabled: true],
            [match:'AlmaLinux 9', name:'AlmaLinux 9', sortOrder:9, instanceType:'almalinux', version:'9', os:'linux', map:true,
             checkType:'vmCheck', statType:'centos', logType:'centos', commType: 'SSH', commPort: 22, commEnabled: true],

            [match:'Rocky Linux 8', name:'Rocky Linux 8', sortOrder:8, instanceType:'rocky', version:'8', os:'linux', map:true,
             checkType:'vmCheck', statType:'centos', logType:'centos', commType: 'SSH', commPort: 22, commEnabled: true],
            [match:'Rocky Linux 9', name:'Rocky Linux 9', sortOrder:9, instanceType:'rocky', version:'9', os:'linux', map:true,
             checkType:'vmCheck', statType:'centos', logType:'centos', commType: 'SSH', commPort: 22, commEnabled: true],

    ]
}