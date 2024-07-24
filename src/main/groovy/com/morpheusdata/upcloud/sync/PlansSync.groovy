package com.morpheusdata.upcloud.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ProvisionType
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.ServicePlanPriceSet
import com.morpheusdata.model.projection.AccountPriceIdentityProjection
import com.morpheusdata.model.projection.AccountPriceSetIdentityProjection
import com.morpheusdata.model.projection.ServicePlanIdentityProjection
import com.morpheusdata.model.projection.ServicePlanPriceSetIdentityProjection
import com.morpheusdata.upcloud.UpcloudPlugin
import com.morpheusdata.upcloud.services.UpcloudApiService
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.model.AccountPrice
import com.morpheusdata.model.AccountPriceSet
import com.morpheusdata.model.StorageVolumeType
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

@Slf4j
class PlansSync {
    private Cloud cloud
    UpcloudPlugin plugin
    private MorpheusContext morpheusContext

    PlansSync(Cloud cloud, UpcloudPlugin plugin) {
        this.cloud = cloud
        this.plugin = plugin
        this.morpheusContext = plugin.morpheusContext
    }

    def execute() {
        try {
            def authConfig = plugin.getAuthConfig(cloud)
            def planListResults = UpcloudApiService.listPlans(authConfig)

            if (planListResults.success == true) {
                def upcloudProvisionType = new ProvisionType(code:'upcloud')
                def planListRecords = morpheusContext.async.servicePlan.listIdentityProjections(
                        new DataQuery().withFilter("provisionType", upcloudProvisionType)
                        .withFilter('active', true)
                )

                planListResults << getCustomServicePlan()
                SyncTask<ServicePlanIdentityProjection, Map, ServicePlan> syncTask = new SyncTask<>(planListRecords, planListResults as Collection<Map>) as SyncTask<ServicePlanIdentityProjection, Map, ServicePlan>
                syncTask.addMatchFunction { ServicePlanIdentityProjection morpheusItem, Map cloudItem ->
                    morpheusItem.externalId == cloudItem?.name
                }.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<ServicePlanIdentityProjection, Map>> updateItems ->
                    morpheusContext.async.servicePlan.listById(updateItems.collect { it.existingItem.id } as List<Long>)
                }.onAdd { itemsToAdd ->
                    addMissingPlans(itemsToAdd)
                }.onUpdate { List<SyncTask.UpdateItem<ServicePlan, Map>> updateItems ->
                    updateMatchedPlans(updateItems)
                }.onDelete { removeItems ->
                    removeMissingPlans(removeItems)
                }.start()

            } else {
                log.error "Error in getting plans: ${planListResults}"
            }
        } catch(e) {
            log.error("plansSync error: ${e}", e)
        }
    }

    private addMissingPlans(Collection<Map> addList) {
        def saves = []
        def upcloudProvisionType = new ProvisionType(code:'upcloud')

        try {
            for (cloudItem in addList) {
                def name = (cloudItem.custom == true) ? cloudItem.name : getNameForPlan(cloudItem)
                def servicePlan = new ServicePlan(
                        code:"upcloud.plan.${cloudItem.name}",
                        provisionType:upcloudProvisionType,
                        description:name,
                        name:name,
                        editable:false,
                        externalId:cloudItem.name,
                        maxCores:cloudItem.core_number,
                        maxMemory:cloudItem.memory_amount.toLong() * ComputeUtility.ONE_MEGABYTE,
                        maxStorage:cloudItem.storage_size.toLong() * ComputeUtility.ONE_GIGABYTE,
                        sortOrder:cloudItem.memory_amount.toLong(),
                        customMaxDataStorage:true,
                        deletable: false,
                        active: cloud.defaultPlanSyncActive,
                        addVolumes:true
                )
                if(cloudItem.custom == true) {
                    servicePlan.deletable = false
                    servicePlan.sortOrder = 131072l
                    servicePlan.customCores = true
                    servicePlan.customMaxStorage = true
                    servicePlan.customMaxMemory = true
                    servicePlan.customMaxDataStorage = true
                    servicePlan.internalId = 'custom'
                }
                saves << servicePlan
            }

            def createResponse = morpheusContext.async.servicePlan.bulkCreate(saves).blockingGet()
            def servicePlans = createResponse.persistedItems
            syncPlanPrices(servicePlans)
        } catch(e) {
            log.error("addMissingPlans error: ${e}", e)
        }
    }

    private updateMatchedPlans(List<SyncTask.UpdateItem<ServicePlan, Map>> updateList) {
        def saves = []

        try {
            for(updateMap in updateList) {
                def matchedItem = updateMap.masterItem
                def plan = updateMap.existingItem
                def name = (matchedItem.custom == true) ? matchedItem.name : getNameForPlan(matchedItem)
                def save = false
                if (plan.name != name) {
                    plan.name = name
                    save = true
                }
                if (plan.description != name) {
                    plan.description = name
                    save = true
                }
                if (plan.maxStorage != matchedItem.storage_size.toLong() * ComputeUtility.ONE_GIGABYTE) {
                    plan.maxStorage = matchedItem.storage_size.toLong() * ComputeUtility.ONE_GIGABYTE
                    save = true
                }
                if (plan.maxMemory != matchedItem.memory_amount.toLong() * ComputeUtility.ONE_MEGABYTE) {
                    plan.maxMemory = matchedItem.memory_amount.toLong() * ComputeUtility.ONE_MEGABYTE
                    save = true
                }

                if (save) {
                    saves << plan
                }
            }
            def updateResponse = morpheusContext.async.servicePlan.bulkSave(saves).blockingGet()
            def servicePlans = updateResponse.persistedItems
            syncPlanPrices(servicePlans)
        } catch(e) {
            log.error("updateMatchedPlans error: ${e}", e)
        }
    }

    def removeMissingPlans(List removeList) {
        def saves = []
        removeList?.each { ServicePlan it ->
            it.active = false
            it.deleted = true
            saves << it
        }
        morpheusContext.async.servicePlan.bulkSave(saves).blockingGet()
    }

    def syncPlanPrices(List<ServicePlan> servicePlans) {
        List<String> priceSetCodes = []
        List<AccountPriceSet> priceSets = []
        List<AccountPrice> prices = []
        Map<String, ServicePlan> priceSetPlans = [:]
        Map<String, ServicePlan> priceSetPrices = [:]

        def authConfig = plugin.getAuthConfig(cloud)
        def priceListResults = UpcloudApiService.listPrices(authConfig)
        def upcloudProvisionType = new ProvisionType(code:'upcloud')

        List<String> servicePlanCodes = servicePlans.collect { it.code }
        Map<String, ServicePlan> tmpServicePlanMap = morpheusContext.async.servicePlan.listByCode(servicePlanCodes).distinct { it.code }.toList().blockingGet().collectEntries { [(it.code):it]}

        priceListResults?.data?.prices?.zone?.each { cloudPriceData ->
            def regionCode = cloudPriceData.name
            def regionName = zoneList.find { it.id == regionCode }?.name
            AccountPrice storagePrice = new AccountPrice(
                    name         : "UpCloud - MaxIOPs - (${regionName})",
                    code         : "upcloud.price.storage_maxiops.${regionCode}",
                    priceType    : AccountPrice.PRICE_TYPE.storage,
                    systemCreated: true,
                    incurCharges : 'always',
                    volumeType   : new StorageVolumeType(code:'upcloudVolume'),
                    cost         : new BigDecimal(cloudPriceData["storage_maxiops"]?.price?.toString() ?: '0.0') / new BigDecimal("100.0"),
                    priceUnit    : 'hour'
            )

            // Iterate the preconfigured plans
            servicePlans?.each { cloudPlan ->
                def planName = cloudPlan.name
                ServicePlan currentServicePlan = new ServicePlan(provisionType: upcloudProvisionType, externalId: planName, active: true)
                if (currentServicePlan && currentServicePlan.internalId != 'custom') {
                    def priceSetCode = "upcloud.plan.${planName}.${regionCode}".toString()
                    if(!priceSetCodes.contains(priceSetCode)) {
                        priceSetCodes << priceSetCode
                        def tmpServicePlan = tmpServicePlanMap[cloudPlan.code]
                        cloudPlan.id = tmpServicePlan.id
                        priceSetPlans[priceSetCode] = cloudPlan

                        def name = "UpCloud - ${planName} (${regionName})"
                        AccountPriceSet priceSet = new AccountPriceSet(
                                code         : priceSetCode,
                                regionCode   : regionCode,
                                name         : name,
                                priceUnit    : 'hour',
                                type         : AccountPriceSet.PRICE_SET_TYPE.fixed.toString(),
                                systemCreated: true
                        )
                        priceSets << priceSet

                        def priceCode = "upcloud.price.${planName}.${regionCode}".toString()
                        AccountPrice price = new AccountPrice(
                                name         : name,
                                code         : priceCode,
                                priceType    : AccountPrice.PRICE_TYPE.fixed,
                                systemCreated: true,
                                cost         : new BigDecimal(cloudPriceData["server_plan_${planName}"]?.price?.toString() ?: '0.0') / 100.0,
                                priceUnit    : 'hour'
                        )
                        prices << price
                        priceSetPrices[priceSetCode] = price
                    }
                }
            }

            Map customPlanOpts = getCustomServicePlan()
            def customPlan = morpheusContext.async.servicePlan.find(
                    new DataQuery().withFilter("code",'upcloud.plan.Custom UpCloud')
                    .withFilter("active", true)
            ).blockingGet()

            syncCustomPlan(customPlan, cloudPriceData, storagePrice)

            // Account Price Set
            Observable<AccountPriceSetIdentityProjection> existingPriceSets = morpheusContext.async.accountPriceSet.listSyncProjectionsByCode(priceSetCodes)
            SyncTask<AccountPriceSetIdentityProjection, AccountPriceSet, AccountPriceSet> syncTask = new SyncTask(existingPriceSets, priceSets)
            syncTask.addMatchFunction { AccountPriceSetIdentityProjection projection, AccountPriceSet cloudItem ->
                return projection.code == cloudItem.code
            }.onDelete { List<AccountPriceSetIdentityProjection> deleteList ->
                def deleteIds = deleteList.collect { it.id }
                List<ServicePlanPriceSet> servicePlanPriceSetDeleteList = morpheusContext.async.servicePlanPriceSet.listByAccountPriceSetIds(deleteIds).toList().blockingGet()
                Boolean servicePlanPriceSetDeleteResult = morpheusContext.async.servicePlanPriceSet.remove(servicePlanPriceSetDeleteList).blockingGet()
                if(servicePlanPriceSetDeleteResult) {
                    morpheusContext.async.accountPriceSet.remove(deleteList).blockingGet()
                } else {
                    log.error("Failed to delete ServicePlanPriceSets associated to AccountPriceSet")
                }
            }.onAdd { List<AccountPriceSet> createList ->
                while (createList.size() > 0) {
                    List chunkedList = createList.take(50)
                    createList = createList.drop(50)
                    createPriceSets(chunkedList, priceSetPlans)
                }
            }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<AccountPriceSetIdentityProjection, AccountPriceSet>> updateItems ->
                Map<Long, SyncTask.UpdateItemDto<AccountPriceSetIdentityProjection, AccountPriceSet>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it]}
                morpheusContext.async.accountPriceSet.listById(updateItems.collect { it.existingItem.id } as Collection<Long>).map {AccountPriceSet priceSet ->
                    SyncTask.UpdateItemDto<AccountPriceSetIdentityProjection, AccountPriceSet> matchItem = updateItemMap[priceSet.id]
                    return new SyncTask.UpdateItem<AccountPriceSet,AccountPriceSet>(existingItem:priceSet, masterItem:matchItem.masterItem)
                }
            }.onUpdate { updateList ->
                while (updateList.size() > 0) {
                    List chunkedList = updateList.take(50)
                    updateList = updateList.drop(50)
                    updateMatchedPriceSet(chunkedList, priceSetPlans)
                }
            }.observe().blockingSubscribe() { complete ->
                if(complete) {
                    Observable<AccountPriceIdentityProjection> existingPrices = morpheusContext.async.accountPrice.listSyncProjectionsByCode(priceSetCodes)
                    SyncTask<AccountPriceIdentityProjection, AccountPrice, AccountPrice> priceSyncTask = new SyncTask(existingPrices, prices)
                    priceSyncTask.addMatchFunction { AccountPriceIdentityProjection projection, AccountPrice apiItem ->
                        projection.code == apiItem.code
                    }.onDelete { List<AccountPriceIdentityProjection> deleteList ->
                        morpheusContext.async.accountPrice.remove(deleteList).blockingGet()
                    }.onAdd { createList ->
                        while(createList.size() > 0) {
                            List chunkedList = createList.take(50)
                            createList = createList.drop(50)
                            createPrice(chunkedList)
                        }
                    }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<AccountPriceIdentityProjection, AccountPrice>> updateItems ->
                        Map<Long, SyncTask.UpdateItemDto<AccountPriceIdentityProjection, AccountPrice>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it] }
                        morpheusContext.async.accountPrice.listById(updateItems.collect { it.existingItem.id } as Collection<Long>).map { AccountPrice price ->
                            SyncTask.UpdateItemDto<AccountPriceIdentityProjection, AccountPrice> matchItem = updateItemMap[price.id]
                            return new SyncTask.UpdateItem<AccountPrice, AccountPrice>(existingItem: price, masterItem: matchItem.masterItem)
                        }
                    }.onUpdate { updateList ->
                        while (updateList.size() > 0) {
                            List chunkedList = updateList.take(50)
                            updateList = updateList.drop(50)
                            updateMatchedPrice(chunkedList)
                        }
                    }.start()
                }
            }
        }
    }

    def createPrice(List<AccountPrice> createList) {
        Boolean itemsCreated = morpheusContext.async.accountPrice.create(createList).blockingGet()
        if(itemsCreated) {
            List<String> priceSetCodes = createList.collect { it.code }
            Map<String, AccountPriceSet> tmpPriceSets = morpheusContext.accountPriceSet.listByCode(priceSetCodes).toList().blockingGet().collectEntries { [(it.code): it] }
            morpheusContext.async.accountPrice.listByCode(priceSetCodes).blockingSubscribe { AccountPrice price ->
                AccountPriceSet priceSet = tmpPriceSets[price.code]
                if(priceSet) {
                    morpheusContext.async.accountPriceSet.addToPriceSet(priceSet, price).blockingGet()
                } else {
                    log.error("createPrice addToPriceSet: Could not find matching price set for code {}", price.code)
                }
            }
        }
    }

    def updateMatchedPrice(List<SyncTask.UpdateItem<AccountPrice,AccountPrice>> updateItems) {
        // update price for pricing changes
        List<AccountPrice> itemsToUpdate = []
        Map<Long, BigDecimal> updateCostMap = [:]
        updateItems.each {it ->
            AccountPrice remoteItem = it.masterItem
            AccountPrice localItem = it.existingItem
            def doSave = false

            if(localItem.name != remoteItem.name) {
                localItem.name = remoteItem.name
                doSave = true
            }

            if(localItem.cost != remoteItem.cost) {
                log.debug("cost doesn't match, updating: local: $localItem.cost, remote: $remoteItem.cost")
                localItem.cost = remoteItem.cost
                doSave = true
            }

            if(localItem.priceType != remoteItem.priceType) {
                localItem.priceType = remoteItem.priceType
                doSave = true
            }

            if(localItem.incurCharges != remoteItem.incurCharges) {
                localItem.incurCharges = remoteItem.incurCharges
                doSave = true
            }

            if(localItem.priceUnit != remoteItem.priceUnit) {
                localItem.priceUnit = remoteItem.priceUnit
                doSave = true
            }

            if(localItem.currency != remoteItem.currency) {
                localItem.currency = remoteItem.currency
                doSave = true
            }

            if(doSave) {
                updateCostMap[localItem.id] = remoteItem.cost
                itemsToUpdate << localItem
            }
        }

        if(itemsToUpdate.size() > 0) {
            Boolean itemsUpdated = morpheusContext.async.accountPrice.save(itemsToUpdate).blockingGet()
            if(itemsUpdated) {
                List<String> priceSetCodes = itemsToUpdate.collect { it.code }
                Map<String, AccountPriceSet> tmpPriceSets = morpheusContext.async.accountPriceSet.listByCode(priceSetCodes).toList().blockingGet().collectEntries { [(it.code): it] }
                morpheusContext.async.accountPrice.listByCode(priceSetCodes).blockingSubscribe { AccountPrice price ->
                    AccountPriceSet priceSet = tmpPriceSets[price.code]
                    BigDecimal matchedCost = updateCostMap[price.id]
                    if(matchedCost != null) {
                        price.cost = matchedCost
                    }
                    if(priceSet) {
                        morpheusContext.async.accountPriceSet.addToPriceSet(priceSet, price).blockingGet()
                    } else {
                        log.error("createPrice addToPriceSet: Could not find matching price set for code {}", price.code)
                    }
                }
            }
        }
    }

    def createPriceSets(List<AccountPriceSet> createList, Map<String, ServicePlan> priceSetPlans) {
        Boolean priceSetsCreated = morpheusContext.async.accountPriceSet.create(createList).blockingGet()
        if(priceSetsCreated) {
            List<AccountPriceSet> tmpPriceSets = morpheusContext.async.accountPriceSet.listByCode(createList.collect { it.code }).distinct{it.code }.toList().blockingGet()
            syncServicePlanPriceSets(tmpPriceSets, priceSetPlans)
        }
    }

    def updateMatchedPriceSet(List<SyncTask.UpdateItem<AccountPriceSet,AccountPriceSet>> updateItems, Map<String, ServicePlan> priceSetPlans) {
        List<AccountPriceSet> itemsToUpdate = []
        updateItems.each {it ->
            AccountPriceSet remoteItem = it.masterItem
            AccountPriceSet localItem = it.existingItem
            def save = false

            if(localItem.name != remoteItem.name) {
                localItem.name = remoteItem.name
                save = true
            }

            if(save) {
                itemsToUpdate << localItem
            }
        }

        if(itemsToUpdate.size() > 0) {
            morpheusContext.accountPriceSet.save(itemsToUpdate).blockingGet()
        }

        syncServicePlanPriceSets(updateItems.collect { it.existingItem }, priceSetPlans)
    }

    def syncServicePlanPriceSets(List<AccountPriceSet> priceSets, Map<String, ServicePlan> priceSetPlans) {
        Map<String, ServicePlanPriceSet> cloudItems = [:]

        // make sure we have a distinct list of price sets to prevent duplicate service plan price sets.
        // this is primarily an issue when the data already had duplicates, we will continue to create duplicates
        // and compound the problem.
        priceSets?.collect { AccountPriceSet priceSet ->
            if(cloudItems[priceSet.code] == null) {
                cloudItems[priceSet.code] = new ServicePlanPriceSet(priceSet: priceSet, servicePlan: priceSetPlans[priceSet.code])
            }
        }

        Observable<ServicePlanPriceSetIdentityProjection> existingItems = morpheusContext.servicePlanPriceSet.listSyncProjections(priceSets)
        SyncTask<ServicePlanPriceSetIdentityProjection, ServicePlanPriceSet, ServicePlanPriceSet> syncTask = new SyncTask(existingItems, cloudItems.values())
        syncTask.addMatchFunction { ServicePlanPriceSetIdentityProjection projection, ServicePlanPriceSet cloudItem ->
            return (projection.priceSet.code == cloudItem.priceSet.code && projection.servicePlan.code == cloudItem.servicePlan.code)
        }.onDelete { List<ServicePlanPriceSetIdentityProjection> deleteList ->
            morpheusContext.async.servicePlanPriceSet.remove(deleteList).blockingGet()
        }.onAdd { createList ->
            while(createList.size() > 0) {
                List chunkedList = createList.take(50)
                createList = createList.drop(50)
                morpheusContext.async.servicePlanPriceSet.create(chunkedList).blockingGet()
            }
        }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<ServicePlanPriceSetIdentityProjection, ServicePlanPriceSet>> updateItems ->
            Map<Long, SyncTask.UpdateItemDto<ServicePlanPriceSetIdentityProjection, ServicePlanPriceSet>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it] }
            morpheusContext.async.servicePlanPriceSet.listById(updateItems.collect { it.existingItem.id } as Collection<Long>).map { ServicePlanPriceSet servicePlanPriceSet ->
                SyncTask.UpdateItemDto<ServicePlanPriceSetIdentityProjection, ServicePlanPriceSet> matchItem = updateItemMap[servicePlanPriceSet.id]
                return new SyncTask.UpdateItem<ServicePlanPriceSet, ServicePlanPriceSet>(existingItem: servicePlanPriceSet, masterItem: matchItem.masterItem)
            }
        }.onUpdate { updateList ->
            // do nothing
        }.start()
    }

    private syncCustomPlan(ServicePlan customPlan, cloudPriceData, AccountPrice storagePrice) {
        def planName = customPlan.name
        def regionCode = cloudPriceData.name
        def regionName = zoneList.find { it.id == regionCode}?.name
        def HOURS_PER_MONTH = 24 * 30

        // Get or create the price set
        def priceSetCode = "upcloud.plan.${planName}.${regionCode}"
        def name = "${planName} (${regionName})"
        def priceSet = new AccountPriceSet(
                code: priceSetCode,
                regionCode: regionCode,
                name: name,
                priceUnit: 'month',
                type: AccountPriceSet.PRICE_SET_TYPE.component.toString(),
                systemCreated: true
        )

        // Get or create the prices
        // First.. memory
        def priceCode = "upcloud.price.${planName}.${regionCode}.memory"
        def cloudPricePerHour =  new BigDecimal(cloudPriceData["server_memory"]?.price?.toString() ?: '0.0') / 100.0
        def cloudPricePerUnitMB = new BigDecimal(cloudPriceData["server_memory"]?.amount?.toString() ?: '256')
        def cloudPricePerMB = (cloudPricePerHour / cloudPricePerUnitMB ) * HOURS_PER_MONTH
        def memoryPrice = new AccountPrice(
                name: "UpCloud - Custom Memory (${regionName})",
                code: priceCode,
                priceType: AccountPrice.PRICE_TYPE.memory,
                systemCreated: true,
                cost: cloudPricePerMB,
                priceUnit: 'month'
        )
        morpheusContext.async.accountPriceSet.addToPriceSet(priceSet, memoryPrice).blockingGet()

        // Next.. core
        priceCode = "upcloud.price.${planName}.${regionCode}.core"
        def cloudPricePerCore =  (new BigDecimal(cloudPriceData["server_core"]?.price?.toString() ?: '0.0') / 100.0) * HOURS_PER_MONTH
        def corePrice = new AccountPrice(
                name: "UpCloud - Custom Core (${regionName})",
                code: priceCode,
                priceType: AccountPrice.PRICE_TYPE.cores,
                systemCreated: true,
                cost: cloudPricePerCore,
                priceUnit: 'month'
        )
        morpheusContext.async.accountPriceSet.addToPriceSet(priceSet, corePrice)

        // Next... stub out a default one for cpu
        priceCode = "upcloud.price.${planName}.${regionCode}.cpu"
        def cpuPrice = new AccountPrice(
                name: "UpCloud - Custom Cpu (${regionName})",
                code: priceCode,
                priceType: AccountPrice.PRICE_TYPE.cpu,
                systemCreated: true,
                cost: new BigDecimal('0.0'),
                priceUnit: 'month'
        )
        morpheusContext.async.accountPriceSet.addToPriceSet(priceSet, cpuPrice)

        // Add the storage price
        def storageMonthPrice = new AccountPrice(
                name: "UpCloud - MaxIOPs - (${regionName})",
                code: "upcloud.price.storage_maxiops.month.${regionCode}",
                priceType: AccountPrice.PRICE_TYPE.storage,
                systemCreated: true,
                volumeType: new StorageVolumeType(code:'upcloudVolume'),
                incurCharges: 'always',
                cost: new BigDecimal(((storagePrice?.cost ?: '0.0') * HOURS_PER_MONTH).toString()),
                priceUnit: 'month'
        )
        morpheusContext.async.accountPriceSet.addToPriceSet(priceSet, storageMonthPrice)

        // Add the set to the correct service plan
        def spps = new ServicePlanPriceSet(servicePlan: customPlan, priceSet: priceSet)
        morpheusContext.async.servicePlanPriceSet.create(spps).blockingGet()
    }

    private static getCustomServicePlan() {
        def rtn = [name:'Custom UpCloud', core_number:1, memory_amount:1024l,
                   storage_size:30l, custom:true]
        return rtn
    }

    private static getNameForPlan(planData) {
        def memoryName = planData.memory_amount < 1000 ? "${planData.memory_amount} MB" : "${planData.memory_amount.div(ComputeUtility.ONE_KILOBYTE)} GB"
        return "UpCloud ${planData.core_number} CPU, ${memoryName} Memory, ${planData.storage_size} GB Storage"
    }

    static zoneList = [
            [id:'de-fra1', name:'Frankfurt #1', available:true],
            [id:'fi-hel1', name:'Helsinki #1', available:true],
            [id:'nl-ams1', name:'Amsterdam #1', available:true],
            [id:'sg-sin1', name:'Singapore #1', available:true],
            [id:'uk-lon1', name:'London #1', available:true],
            [id:'us-chi1', name:'Chicago #1', available:true]
    ]
}
