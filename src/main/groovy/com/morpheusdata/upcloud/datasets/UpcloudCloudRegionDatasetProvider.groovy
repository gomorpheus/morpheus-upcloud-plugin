package com.morpheusdata.upcloud.datasets

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.data.DatasetInfo
import com.morpheusdata.core.data.DatasetQuery
import com.morpheusdata.core.providers.AbstractDatasetProvider
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudRegion
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.upcloud.UpcloudPlugin
import com.morpheusdata.upcloud.services.UpcloudApiService
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

@Slf4j
class UpcloudCloudRegionDatasetProvider extends AbstractDatasetProvider<CloudRegion, Long>{

    public static final providerName = 'Upcloud Cloud Dataset Provider'
    public static final providerNamespace = 'upcloud'
    public static final providerKey = 'upcloudCloudDataset'
    public static final providerDescription = 'Get clouds from Upcloud'

    UpcloudApiService upcloudApiService
    MorpheusContext morpheusContext
    UpcloudPlugin plugin

    UpcloudCloudRegionDatasetProvider(Plugin plugin, MorpheusContext morpheus) {
        this.plugin = plugin
        this.morpheusContext = morpheus
        this.upcloudApiService = new UpcloudApiService()
    }

    @Override
    DatasetInfo getInfo() {
        return new DatasetInfo(
                name: providerName,
                namespace: providerNamespace,
                key: providerKey,
                description: providerDescription
        )
    }

    @Override
    Class<CloudRegion> getItemType() {
        return CloudRegion.class
    }

    Observable<CloudRegion> list(DatasetQuery query) {
        Map authConfig = plugin.getAuthConfig(query.parameters)
        ServiceResponse apiResults = upcloudApiService.listZones(authConfig)
        if(apiResults.success) {
            return Observable.fromIterable((List<CloudRegion>)apiResults.data.zones.zone)
        }
        return Observable.empty()
    }

    Observable<CloudRegion> listOptions(DatasetQuery query) {
        log.debug("LIST OPTIONS 64: ${query}")
        return list(query).map { [name: it.description, value: it.id] }
    }

    CloudRegion fetchItem(Object value) {
        def rtn = null
        if(value instanceof Long) {
            rtn = item((Long)value)
        } else if(value instanceof CharSequence) {
            def longValue = value.isNumber() ? value.toLong() : null
            if(longValue) {
                rtn = item(longValue)
            }
        }
        return rtn
    }

    CloudRegion item(Long value) {
        def rtn = list(new DatasetQuery()).find{ it.id == value }
        return rtn
    }

    String itemName(CloudRegion item) {
        return item.name
    }

    Long itemValue(CloudRegion item) {
        return (Long)item.id
    }

    @Override
    boolean isPlugin() {
        return true
    }
}
