package com.microsoft.jenkins.azuread;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.microsoft.graph.models.DirectoryObject;
import com.microsoft.graph.models.DirectoryObjectCollectionResponse;
import com.microsoft.graph.models.Group;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import okhttp3.Request;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public final class AzureCachePool {
    private static final Logger LOGGER = Logger.getLogger(AzureCachePool.class.getName());
    private static Cache<String, List<AzureAdGroup>> belongingGroupsByOid =
            Caffeine.newBuilder().expireAfterAccess(1, TimeUnit.HOURS).build();
    private final GraphServiceClient azure;

    private AzureCachePool(GraphServiceClient azure) {
        this.azure = azure;
    }

    public static AzureCachePool get(GraphServiceClient azure) {
        return new AzureCachePool(azure);
    }

    public List<AzureAdGroup> getBelongingGroupsByOid(final String oid) {
        List<AzureAdGroup> result = belongingGroupsByOid.get(oid,
                (cacheKey) -> {
                    try {
                        DirectoryObjectCollectionResponse collection = azure
                                .users()
                                .byUserId(oid)
                                // TODO asGroup isn't working json error, and neither is $filter on securityEnabled
                                .transitiveMemberOf()
                                .get();


                        List<AzureAdGroup> groups = new ArrayList<>();

                        while (collection != null) {
                            final List<DirectoryObject> directoryObjects = collection.getValue();

                            List<AzureAdGroup> groupsFromPage = directoryObjects.stream()
                                    .map(group -> {
                                        if (group instanceof Group) {
                                            return new AzureAdGroup(group.id, ((Group) group).displayName);
                                        }
                                        return null;
                                    })
                                    .filter(Objects::nonNull)
                                    .toList();
                            groups.addAll(groupsFromPage);

                            DirectoryObjectCollectionWithReferencesRequestBuilder nextPage = collection
                                    .getNextPage();
                            if (nextPage == null) {
                                break;
                            } else {
                                collection = nextPage.buildRequest().get();
                            }
                        }

                        return groups;
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Do not have sufficient privileges to "
                                + "fetch your belonging groups' authorities.", e);
                        return Collections.emptyList();
                    }

                });
        if (Constants.DEBUG) {
            belongingGroupsByOid.invalidate(oid);
        }
        return result;

    }

    public static void invalidateBelongingGroupsByOid(String userId) {
        belongingGroupsByOid.invalidate(userId);
    }

}
