package com.jivesoftware.os.tasmo.lib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.jivesoftware.os.jive.utils.base.interfaces.CallbackStream;
import com.jivesoftware.os.jive.utils.id.ChainedVersion;
import com.jivesoftware.os.jive.utils.id.Id;
import com.jivesoftware.os.jive.utils.id.IdProvider;
import com.jivesoftware.os.jive.utils.id.ImmutableByteArray;
import com.jivesoftware.os.jive.utils.id.ObjectId;
import com.jivesoftware.os.jive.utils.id.TenantId;
import com.jivesoftware.os.jive.utils.id.TenantIdAndCentricId;
import com.jivesoftware.os.jive.utils.ordered.id.OrderIdProvider;
import com.jivesoftware.os.jive.utils.row.column.value.store.api.ColumnValueAndTimestamp;
import com.jivesoftware.os.jive.utils.row.column.value.store.api.RowColumnValueStore;
import com.jivesoftware.os.jive.utils.row.column.value.store.inmemory.RowColumnValueStoreImpl;
import com.jivesoftware.os.tasmo.configuration.views.TenantViewsProvider;
import com.jivesoftware.os.tasmo.event.api.JsonEventConventions;
import com.jivesoftware.os.tasmo.event.api.write.Event;
import com.jivesoftware.os.tasmo.event.api.write.EventWriteException;
import com.jivesoftware.os.tasmo.event.api.write.EventWriter;
import com.jivesoftware.os.tasmo.event.api.write.EventWriterOptions;
import com.jivesoftware.os.tasmo.event.api.write.EventWriterResponse;
import com.jivesoftware.os.tasmo.event.api.write.JsonEventWriteException;
import com.jivesoftware.os.tasmo.event.api.write.JsonEventWriter;
import com.jivesoftware.os.tasmo.lib.TasmoReadMaterializationInitializer.TasmoReadMaterializationConfig;
import com.jivesoftware.os.tasmo.lib.TasmoServiceInitializer.TasmoServiceConfig;
import com.jivesoftware.os.tasmo.lib.TasmoSyncWriteInitializer.TasmoSyncWriteConfig;
import com.jivesoftware.os.tasmo.lib.concur.ConcurrencyAndExistenceCommitChange;
import com.jivesoftware.os.tasmo.lib.process.WrittenEventContext;
import com.jivesoftware.os.tasmo.lib.process.WrittenEventProcessorDecorator;
import com.jivesoftware.os.tasmo.lib.process.bookkeeping.BookkeepingEvent;
import com.jivesoftware.os.tasmo.lib.process.notification.ViewChangeNotificationProcessor;
import com.jivesoftware.os.tasmo.lib.write.CommitChange;
import com.jivesoftware.os.tasmo.lib.write.CommitChangeException;
import com.jivesoftware.os.tasmo.lib.write.PathId;
import com.jivesoftware.os.tasmo.lib.write.ViewFieldChange;
import com.jivesoftware.os.tasmo.model.Views;
import com.jivesoftware.os.tasmo.model.ViewsProcessorId;
import com.jivesoftware.os.tasmo.model.ViewsProvider;
import com.jivesoftware.os.tasmo.model.path.MurmurHashViewPathKeyProvider;
import com.jivesoftware.os.tasmo.model.path.ViewPathKeyProvider;
import com.jivesoftware.os.tasmo.model.process.JsonWrittenEventProvider;
import com.jivesoftware.os.tasmo.model.process.OpaqueFieldValue;
import com.jivesoftware.os.tasmo.model.process.WrittenEvent;
import com.jivesoftware.os.tasmo.model.process.WrittenEventProvider;
import com.jivesoftware.os.tasmo.reference.lib.ClassAndField_IdKey;
import com.jivesoftware.os.tasmo.reference.lib.concur.ConcurrencyStore;
import com.jivesoftware.os.tasmo.reference.lib.concur.HBaseBackedConcurrencyStore;
import com.jivesoftware.os.tasmo.view.reader.api.ViewDescriptor;
import com.jivesoftware.os.tasmo.view.reader.api.ViewReadMaterializer;
import com.jivesoftware.os.tasmo.view.reader.api.ViewResponse;
import com.jivesoftware.os.tasmo.view.reader.service.AllAllowedViewPermissionsChecker;
import com.jivesoftware.os.tasmo.view.reader.service.JsonViewMerger;
import com.jivesoftware.os.tasmo.view.reader.service.StaleViewFieldStream;
import com.jivesoftware.os.tasmo.view.reader.service.ViewAsObjectNode;
import com.jivesoftware.os.tasmo.view.reader.service.ViewPermissionChecker;
import com.jivesoftware.os.tasmo.view.reader.service.ViewProvider;
import com.jivesoftware.os.tasmo.view.reader.service.ViewValueReader;
import com.jivesoftware.os.tasmo.view.reader.service.shared.ViewValue;
import com.jivesoftware.os.tasmo.view.reader.service.shared.ViewValueStore;
import com.jivesoftware.os.tasmo.view.reader.service.writer.ViewValueWriter;
import com.jivesoftware.os.tasmo.view.reader.service.writer.ViewWriteFieldChange;
import com.jivesoftware.os.tasmo.view.reader.service.writer.ViewWriterException;
import com.jivesoftware.os.tasmo.view.reader.service.writer.WriteToViewValueStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.merlin.config.BindInterfaceToConfiguration;

/**
 *
 * @author jonathan.colt
 */
public class TasmoMaterializerHarnessFactory {

    static TasmoStorageProvider createInmemoryTasmoStorageProvider() {
        return new TasmoStorageProvider() {
            private final RowColumnValueStore<TenantIdAndCentricId, ObjectId, String, OpaqueFieldValue, RuntimeException> eventStorage = new RowColumnValueStoreImpl<>();
            private final RowColumnValueStore<TenantIdAndCentricId, ObjectId, String, Long, RuntimeException> concurrencyStorage = new RowColumnValueStoreImpl<>();
            private final RowColumnValueStore<TenantIdAndCentricId, ImmutableByteArray, ImmutableByteArray, ViewValue, RuntimeException> viewValueStorage = new RowColumnValueStoreImpl<>();
            private final RowColumnValueStore<TenantIdAndCentricId, ClassAndField_IdKey, ObjectId, byte[], RuntimeException> multiLinksStorage = new RowColumnValueStoreImpl<>();
            private final RowColumnValueStore<TenantIdAndCentricId, ClassAndField_IdKey, ObjectId, byte[], RuntimeException> multiBackLinksStorage = new RowColumnValueStoreImpl<>();

            @Override
            public RowColumnValueStore<TenantIdAndCentricId, ObjectId, String, OpaqueFieldValue, RuntimeException> eventStorage() throws Exception {
                return eventStorage;
            }

            @Override
            public RowColumnValueStore<TenantIdAndCentricId, ObjectId, String, Long, RuntimeException> concurrencyStorage() throws Exception {
                return concurrencyStorage;
            }

            @Override
            public RowColumnValueStore<TenantIdAndCentricId, ImmutableByteArray, ImmutableByteArray, ViewValue, RuntimeException> viewValueStorage() throws Exception {
                return viewValueStorage;
            }

            @Override
            public RowColumnValueStore<TenantIdAndCentricId, ClassAndField_IdKey, ObjectId, byte[], RuntimeException> multiLinksStorage() throws Exception {
                return multiLinksStorage;
            }

            @Override
            public RowColumnValueStore<TenantIdAndCentricId, ClassAndField_IdKey, ObjectId, byte[], RuntimeException> multiBackLinksStorage() throws Exception {
                return multiBackLinksStorage;
            }
        };
    }

    static NoOpEventBookkeeper createNoOpEventBookkeeper() {
        return new NoOpEventBookkeeper();
    }

    static class NoOpEventBookkeeper implements CallbackStream<List<BookkeepingEvent>> {

        @Override
        public List<BookkeepingEvent> callback(List<BookkeepingEvent> v) throws Exception {
            return v;
        }
    }

    static ViewChangeNotificationProcessor createNoOpViewChangeNotificationProcessor() {
        return new NoOpViewChangeNotificationProcessor();
    }

    static class NoOpViewChangeNotificationProcessor implements ViewChangeNotificationProcessor {

        @Override
        public void process(WrittenEventContext batchContext, WrittenEvent writtenEvent) throws Exception {
        }
    }

    static ViewPermissionChecker createNoOpViewPermissionChecker() {
        return new AllAllowedViewPermissionsChecker();
    }

    static TasmoMaterializerHarness createWriteTimeMaterializer(
        final TasmoStorageProvider tasmoStorageProvider,
        final CallbackStream<List<BookkeepingEvent>> bookkeepingStream,
        final ViewChangeNotificationProcessor changeNotificationProcessor,
        final ViewPermissionChecker viewPermissionChecker) throws Exception {

        final ChainedVersion currentVersion = new ChainedVersion("0", "1");
        final JsonViewMerger merger = new JsonViewMerger(new ObjectMapper());
        final ViewAsObjectNode viewAsObjectNode = new ViewAsObjectNode();
        final ViewPathKeyProvider pathKeyProvider = new MurmurHashViewPathKeyProvider();
        final WrittenEventProvider<ObjectNode, JsonNode> writtenEventProvider = new JsonWrittenEventProvider();

        final OrderIdProvider idProvider = new OrderIdProvider() {
            private final AtomicLong id = new AtomicLong();

            @Override
            public long nextId() {
                return id.addAndGet(2); // Have to move by twos so there is room for add vs remove differentiation.
            }
        };

        final AtomicReference<Views> views = new AtomicReference<>();
        ViewsProvider viewsProvider = new ViewsProvider() {
            @Override
            public Views getViews(ViewsProcessorId viewsProcessorId) {
                return views.get();
            }

            @Override
            public ChainedVersion getCurrentViewsVersion(TenantId tenantId) {
                return currentVersion;
            }
        };
        RowColumnValueStore<TenantIdAndCentricId, ImmutableByteArray, ImmutableByteArray, ViewValue, RuntimeException> viewValueStorage = tasmoStorageProvider.
            viewValueStorage();
        ViewValueStore viewValueStore = new ViewValueStore(viewValueStorage, pathKeyProvider);

        CommitChange commitChange = createCommitToViewValueStore(viewValueStorage, pathKeyProvider);

        final TasmoBlacklist tasmoBlacklist = new TasmoBlacklist();

        TasmoServiceConfig serviceConfig = BindInterfaceToConfiguration.bindDefault(TasmoServiceConfig.class);

        final TasmoEventIngress tasmoEventIngress = TasmoServiceInitializer.initializeEventIngressCallbackStream(idProvider,
            viewsProvider,
            pathKeyProvider,
            writtenEventProvider,
            tasmoStorageProvider,
            commitChange,
            changeNotificationProcessor,
            bookkeepingStream,
            Optional.<WrittenEventProcessorDecorator>absent(),
            tasmoBlacklist,
            serviceConfig);

        JsonEventWriter jsonEventWriter = jsonEventWriter(idProvider, writtenEventProvider, tasmoEventIngress);
        final EventWriter eventWriter = new EventWriter(jsonEventWriter);
        final ViewExpectations expectations = new ViewExpectations(viewValueStore, pathKeyProvider);

        StaleViewFieldStream staleViewFieldStream = new StaleViewFieldStream() {
            @Override
            public void stream(ViewDescriptor viewDescriptor, ColumnValueAndTimestamp<ImmutableByteArray, ViewValue, Long> value) {
                System.out.println("Encountered stale fields for:" + viewDescriptor + " value:" + value);
            }
        };

        final TenantId masterTenantId = new TenantId("master");
        final TenantViewsProvider tenantViewsProvider = new TenantViewsProvider(masterTenantId, viewsProvider, pathKeyProvider);

        ViewValueReader viewValueReader = new ViewValueReader(viewValueStore);
        final ViewProvider<ViewResponse> viewProvider = new ViewProvider<>(viewPermissionChecker,
            viewValueReader,
            tenantViewsProvider,
            viewAsObjectNode,
            merger,
            staleViewFieldStream,
            1024 * 1024 * 10);

        return new TasmoMaterializerHarness() {

            @Override
            public ObjectId write(Event event) throws EventWriteException {
                return eventWriter.write(event).getObjectIds().get(0);
            }

            @Override
            public List<ObjectId> write(List<Event> events) throws EventWriteException {
                return eventWriter.write(events).getObjectIds();
            }

            @Override
            public void addExpectation(ObjectId rootId, String viewClassName, String viewFieldName, ObjectId[] pathIds, String fieldName, Object value) {
                expectations.addExpectation(rootId, viewClassName, viewFieldName, pathIds, fieldName, value);
            }

            @Override
            public void assertExpectation(TenantIdAndCentricId tenantIdAndCentricId) throws IOException {
                expectations.assertExpectation(tenantIdAndCentricId);
            }

            @Override
            public void clearExpectations() {
                expectations.clear();
            }

            @Override
            public ObjectNode readView(TenantIdAndCentricId tenantIdAndCentricId, Id actorId, ObjectId viewId) throws Exception {
                ViewResponse view = viewProvider.readView(new ViewDescriptor(tenantIdAndCentricId.getTenantId(), actorId, viewId));
                if (view != null && view.hasViewBody()) {
                    return view.getViewBody();
                }
                return null;
            }

            @Override
            public IdProvider idProvider() {
                return new IdProvider() {

                    @Override
                    public Id nextId() {
                        return new Id(idProvider.nextId());
                    }
                };
            }

            @Override
            public void initModel(Views _views) {
                views.set(_views);
                tenantViewsProvider.loadModel(masterTenantId);
                expectations.init(_views);
            }

            @Override
            public String toString() {
                return "WriteMaterializeHarness";
            }
        };
    }

    static TasmoMaterializerHarness createSyncWriteSyncReadsMaterializer(
        final TasmoStorageProvider tasmoStorageProvider,
        final CallbackStream<List<BookkeepingEvent>> bookkeepingStream,
        final ViewChangeNotificationProcessor changeNotificationProcessor,
        final ViewPermissionChecker viewPermissionChecker) throws Exception {

        final OrderIdProvider idProvider = new OrderIdProvider() {
            private final AtomicLong id = new AtomicLong();

            @Override
            public long nextId() {
                return id.addAndGet(2); // Have to move by twos so there is room for add vs remove differentiation.
            }
        };

        final ChainedVersion currentVersion = new ChainedVersion("0", "1");
        final ViewPathKeyProvider pathKeyProvider = new MurmurHashViewPathKeyProvider();
        final AtomicReference<Views> views = new AtomicReference<>();
        ViewsProvider viewsProvider = new ViewsProvider() {
            @Override
            public Views getViews(ViewsProcessorId viewsProcessorId) {
                return views.get();
            }

            @Override
            public ChainedVersion getCurrentViewsVersion(TenantId tenantId) {
                return currentVersion;
            }
        };

        final WrittenEventProvider<ObjectNode, JsonNode> writtenEventProvider = new JsonWrittenEventProvider();
        final TasmoBlacklist tasmoBlacklist = new TasmoBlacklist();

        TasmoSyncWriteConfig syncWriteConfig = BindInterfaceToConfiguration.bindDefault(TasmoSyncWriteConfig.class);

        final TasmoSyncEventWriter syncEventWriter = TasmoSyncWriteInitializer.initialize(viewsProvider,
            pathKeyProvider,
            writtenEventProvider,
            tasmoStorageProvider,
            tasmoBlacklist,
            syncWriteConfig);

        JsonEventWriter jsonEventWriter = jsonEventWriter(idProvider, writtenEventProvider, syncEventWriter);
        final EventWriter eventWriter = new EventWriter(jsonEventWriter);

        TasmoReadMaterializationConfig readMaterializationConfig = BindInterfaceToConfiguration.bindDefault(TasmoReadMaterializationConfig.class);

        RowColumnValueStore<TenantIdAndCentricId, ImmutableByteArray, ImmutableByteArray, ViewValue, RuntimeException> viewValueStorage = tasmoStorageProvider.
            viewValueStorage();

        CommitChange commitChangeVistor = createCommitToViewValueStore(viewValueStorage, pathKeyProvider);
        final ViewReadMaterializer<ViewResponse> viewReadMaterializer = TasmoReadMaterializationInitializer.initialize(readMaterializationConfig,
            viewsProvider, pathKeyProvider, writtenEventProvider, tasmoStorageProvider, viewPermissionChecker, Optional.of(commitChangeVistor));

        ViewValueStore viewValueStore = new ViewValueStore(viewValueStorage, pathKeyProvider);
        final ViewExpectations expectations = new ViewExpectations(viewValueStore, pathKeyProvider);

        return new TasmoMaterializerHarness() {

            @Override
            public ObjectId write(Event event) throws EventWriteException {
                return eventWriter.write(event).getObjectIds().get(0);
            }

            @Override
            public List<ObjectId> write(List<Event> events) throws EventWriteException {
                return eventWriter.write(events).getObjectIds();
            }

            @Override
            public void addExpectation(ObjectId rootId, String viewClassName, String viewFieldName, ObjectId[] pathIds, String fieldName, Object value) {
                expectations.addExpectation(rootId, viewClassName, viewFieldName, pathIds, fieldName, value);
            }

            @Override
            public void assertExpectation(TenantIdAndCentricId tenantIdAndCentricId) throws IOException {
                expectations.assertExpectation(tenantIdAndCentricId);
            }

            @Override
            public void clearExpectations() {
                expectations.clear();
            }

            @Override
            public ObjectNode readView(TenantIdAndCentricId tenantIdAndCentricId, Id actorId, ObjectId viewId) throws Exception {
                ViewResponse view = viewReadMaterializer.readMaterializeView(new ViewDescriptor(tenantIdAndCentricId.getTenantId(), actorId, viewId));
                if (view != null && view.hasViewBody()) {
                    return view.getViewBody();
                }
                return null;
            }

            @Override
            public IdProvider idProvider() {
                return new IdProvider() {

                    @Override
                    public Id nextId() {
                        return new Id(idProvider.nextId());
                    }
                };
            }

            @Override
            public void initModel(Views _views) {
                views.set(_views);
                expectations.init(_views);
            }

            @Override
            public String toString() {
                return "SyncWriteSyncReadHarness";
            }

        };

    }

    public static CommitChange createCommitToViewValueStore(
        RowColumnValueStore<TenantIdAndCentricId, ImmutableByteArray, ImmutableByteArray, ViewValue, RuntimeException> viewValueStorage,
        final ViewPathKeyProvider pathKeyProvider) {

        ViewValueStore viewValueStore = new ViewValueStore(viewValueStorage, pathKeyProvider);
        ViewValueWriter viewValueWriter = new ViewValueWriter(viewValueStore);
        final WriteToViewValueStore writeToViewValueStore = new WriteToViewValueStore(viewValueWriter);
        CommitChange commitChange = new CommitChange() {
            @Override
            public void commitChange(WrittenEventContext batchContext,
                TenantIdAndCentricId tenantIdAndCentricId,
                List<ViewFieldChange> changes) throws CommitChangeException {
                List<ViewWriteFieldChange> write = new ArrayList<>(changes.size());
                for (ViewFieldChange change : changes) {
                    try {
                        PathId[] modelPathInstanceIds = change.getModelPathInstanceIds();
                        ObjectId[] ids = new ObjectId[modelPathInstanceIds.length];
                        for (int i = 0; i < ids.length; i++) {
                            ids[i] = modelPathInstanceIds[i].getObjectId();
                        }
                        ViewWriteFieldChange viewWriteFieldChange = new ViewWriteFieldChange(
                            change.getEventId(),
                            tenantIdAndCentricId,
                            change.getActorId(),
                            ViewWriteFieldChange.Type.valueOf(change.getType().name()),
                            change.getViewObjectId(),
                            change.getModelPathIdHashcode(),
                            ids,
                            new ViewValue(change.getModelPathTimestamps(), change.getValue()),
                            change.getTimestamp());
                        write.add(viewWriteFieldChange);
                        System.out.println("viewWriteFieldChange:" + viewWriteFieldChange);
                    } catch (Exception ex) {
                        throw new CommitChangeException("Failed to add change for the following reason.", ex);
                    }
                }

                try {
                    writeToViewValueStore.write(tenantIdAndCentricId, write);
                } catch (ViewWriterException ex) {
                    throw new CommitChangeException("Failed to write BigInteger?", ex);
                }
            }
        };
        return commitChange;
    }

    static JsonEventWriter jsonEventWriter(final OrderIdProvider idProvider,
        final WrittenEventProvider<ObjectNode, JsonNode> writtenEventProvider,
        final CallbackStream<List<WrittenEvent>> tasmoEventIngress) {

        return new JsonEventWriter() {
            JsonEventConventions jsonEventConventions = new JsonEventConventions();

            @Override
            public EventWriterResponse write(List<ObjectNode> events, EventWriterOptions options) throws JsonEventWriteException {
                try {
                    List<ObjectId> objectIds = Lists.newArrayList();
                    List<Long> eventIds = Lists.newArrayList();
                    for (ObjectNode w : events) {
                        long eventId = jsonEventConventions.getEventId(w);
                        if (eventId == 0) {
                            eventId = idProvider.nextId();
                            jsonEventConventions.setEventId(w, eventId);
                        }
                        eventIds.add(eventId);

                        String instanceClassname = jsonEventConventions.getInstanceClassName(w);
                        ObjectId objectId = new ObjectId(instanceClassname, jsonEventConventions.getInstanceId(w, instanceClassname));
                        objectIds.add(objectId);

                    }

                    List<WrittenEvent> writtenEvents = new ArrayList<>();
                    for (ObjectNode eventNode : events) {
                        writtenEvents.add(writtenEventProvider.convertEvent(eventNode));
                    }

                    List<WrittenEvent> failedToProcess = tasmoEventIngress.callback(writtenEvents);
                    while (!failedToProcess.isEmpty()) {
                        System.out.println("FAILED to process " + failedToProcess.size() + " events likely due to consistency issues.");
                        failedToProcess = tasmoEventIngress.callback(failedToProcess);
                    }

                    return new EventWriterResponse(eventIds, objectIds);

                } catch (Exception ex) {
                    throw new JsonEventWriteException("sad trombone", ex);
                }
            }
        };
    }
}