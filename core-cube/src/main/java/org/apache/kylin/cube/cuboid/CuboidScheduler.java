/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.apache.kylin.cube.cuboid;

import java.io.Serializable;

/**
 */

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import javax.annotation.Nullable;

import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.util.Pair;
import org.apache.kylin.cube.model.AggregationGroup;
import org.apache.kylin.cube.model.CubeDesc;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

@SuppressWarnings("serial")
public class CuboidScheduler implements Serializable {
    private final CubeDesc cubeDesc;
    private final long max;
    private List<List<Long>> cuboidsByLayer;

    public CuboidScheduler(CubeDesc cubeDesc) {
        this.cubeDesc = cubeDesc;
        int size = this.cubeDesc.getRowkey().getRowKeyColumns().length;
        this.max = (long) Math.pow(2, size) - 1;
    }

    public int getCuboidCount() {
        return cubeDesc.getAllCuboids().size();
    }

    public List<Long> getSpanningCuboid(long cuboid) {
        if (cuboid > max || cuboid < 0) {
            throw new IllegalArgumentException("Cuboid " + cuboid + " is out of scope 0-" + max);
        }

        List<Long> spanning = cubeDesc.getParent2Child().get(cuboid);
        if (spanning == null) {
            return Collections.EMPTY_LIST;
        }
        return spanning;
    }

    public int getCardinality(long cuboid) {
        if (cuboid > max || cuboid < 0) {
            throw new IllegalArgumentException("Cubiod " + cuboid + " is out of scope 0-" + max);
        }

        return Long.bitCount(cuboid);
    }

    public List<Long> getAllCuboidIds() {
        return Lists.newArrayList(cubeDesc.getAllCuboids());
    }

    /**
     * Get cuboids by layer. It's built from pre-expanding tree.
     * @return layered cuboids
     */
    public List<List<Long>> getCuboidsByLayer() {
        if (cuboidsByLayer != null) {
            return cuboidsByLayer;
        }

        int totalNum = 0;
        cuboidsByLayer = Lists.newArrayList();

        cuboidsByLayer.add(Collections.singletonList(Cuboid.getBaseCuboidId(cubeDesc)));
        totalNum++;

        List<Long> lastLayer = cuboidsByLayer.get(cuboidsByLayer.size() - 1);
        while (!lastLayer.isEmpty()) {
            List<Long> newLayer = Lists.newArrayList();
            for (Long parent : lastLayer) {
                newLayer.addAll(getSpanningCuboid(parent));
            }
            if (newLayer.isEmpty()) {
                break;
            }
            cuboidsByLayer.add(newLayer);
            totalNum += newLayer.size();
            lastLayer = newLayer;
        }

        int size = getAllCuboidIds().size();
        Preconditions.checkState(totalNum == size, "total Num: " + totalNum + " actual size: " + size);
        return cuboidsByLayer;
    }

    /**
     * Collect cuboid from bottom up, considering all factor including black list
     * Build tree steps:
     * 1. Build tree from bottom up considering dim capping
     * 2. Kick out blacked-out cuboids from the tree
     * 3. Make sure all the cuboids have proper "parent", if not add it to the tree.
     *    Direct parent is not necessary, can jump *forward* steps to find in-direct parent.
     *    For example, forward = 1, grandparent can also be the parent. Only if both parent
     *    and grandparent are missing, add grandparent to the tree.
     * @return Cuboid collection
     */
    public Pair<Set<Long>, Map<Long, List<Long>>> buildTreeBottomUp() {
        int forward = cubeDesc.getParentForward();
        KylinConfig config = cubeDesc.getConfig();

        Set<Long> cuboidHolder = new HashSet<>();

        // build tree structure
        Set<Long> children = getLowestCuboids();
        long maxCombination = config.getCubeAggrGroupMaxCombination() * 10;
        maxCombination = maxCombination < 0 ? Long.MAX_VALUE : maxCombination;
        while (!children.isEmpty()) {
            if (cuboidHolder.size() > maxCombination) {
                throw new IllegalStateException("Too many cuboids for the cube. Cuboid combination reached " + cuboidHolder.size() + " and limit is " + maxCombination + ". Abort calculation.");
            }
            cuboidHolder.addAll(children);
            children = getOnTreeParentsByLayer(children);
        }
        cuboidHolder.add(Cuboid.getBaseCuboidId(cubeDesc));

        // kick off blacked
        cuboidHolder = Sets.newHashSet(Iterators.filter(cuboidHolder.iterator(), new Predicate<Long>() {
            @Override
            public boolean apply(@Nullable Long cuboidId) {
                return !cubeDesc.isBlackedCuboid(cuboidId);
            }
        }));

        // fill padding cuboids
        Map<Long, List<Long>> parent2Child = Maps.newHashMap();
        Queue<Long> cuboidScan = new ArrayDeque<>();
        cuboidScan.addAll(cuboidHolder);
        while (!cuboidScan.isEmpty()) {
            long current = cuboidScan.poll();
            long parent = getParentOnPromise(current, cuboidHolder, forward);

            if (parent > 0) {
                if (!cuboidHolder.contains(parent)) {
                    cuboidScan.add(parent);
                    cuboidHolder.add(parent);
                }
                if (parent2Child.containsKey(parent)) {
                    parent2Child.get(parent).add(current);
                } else {
                    parent2Child.put(parent, Lists.newArrayList(current));
                }
            }
        }

        return Pair.newPair(cuboidHolder, parent2Child);
    }

    private long getParentOnPromise(long child, Set<Long> coll, int forward) {
        long parent = getOnTreeParent(child);
        if (parent < 0) {
            return -1;
        }

        if (coll.contains(parent) || forward == 0) {
            return parent;
        }

        return getParentOnPromise(parent, coll, forward - 1);
    }

    /**
     * Get the parent cuboid really on the spanning tree.
     * @param child an on-tree cuboid
     * @return
     */
    public long getValidParent(long child) {
        long parent = getOnTreeParent(child);
        while (parent > 0) {
            if (cubeDesc.getAllCuboids().contains(parent)) {
                break;
            }
            parent = getOnTreeParent(parent);
        }

        if (parent <= 0) {
            throw new IllegalStateException("Can't find valid parent for Cuboid " + child);
        }
        return parent;
    }

    private long getOnTreeParent(long child) {
        Collection<Long> candidates = getOnTreeParents(child);
        if (candidates == null || candidates.isEmpty()) {
            return -1;
        }
        return Collections.min(candidates, Cuboid.cuboidSelectComparator);
    }

    /**
     * Get all parent for children cuboids, considering dim cap.
     * @param children children cuboids
     * @return all parents cuboids
     */
    private Set<Long> getOnTreeParentsByLayer(Collection<Long> children) {
        Set<Long> parents = new HashSet<>();
        for (long child : children) {
            parents.addAll(getOnTreeParents(child));
        }
        parents = Sets.newHashSet(Iterators.filter(parents.iterator(), new Predicate<Long>() {
            @Override
            public boolean apply(@Nullable Long cuboidId) {
                if (cuboidId == Cuboid.getBaseCuboidId(cubeDesc)) {
                    return true;
                }

                for (AggregationGroup agg : cubeDesc.getAggregationGroups()) {
                    if (agg.isOnTree(cuboidId) && agg.checkDimCap(cuboidId)) {
                        return true;
                    }
                }

                return false;
            }
        }));
        return parents;
    }

    /**
     * Get all *possible* parent for a child cuboid
     * @param child Child cuboid ID
     * @return all *possible* parent cuboids
     */
    private Set<Long> getOnTreeParents(long child) {
        List<AggregationGroup> aggrs = Lists.newArrayList();
        for (AggregationGroup agg : cubeDesc.getAggregationGroups()) {
            if (agg.isOnTree(child)) {
                aggrs.add(agg);
            }
        }

        return getOnTreeParents(child, aggrs);
    }

    /**
     * Get lowest (not Cube building level) Cuboids for every Agg group
     * @return lowest level cuboids
     */
    private Set<Long> getLowestCuboids() {
        return getOnTreeParents(0L, cubeDesc.getAggregationGroups());
    }

    private Set<Long> getOnTreeParents(long child, Collection<AggregationGroup> groups) {
        Set<Long> parentCandidate = new HashSet<>();

        if (child == Cuboid.getBaseCuboidId(cubeDesc)) {
            return parentCandidate;
        }

        for (AggregationGroup agg : groups) {
            if (child == agg.getPartialCubeFullMask()) {
                parentCandidate.add(Cuboid.getBaseCuboidId(cubeDesc));
                return parentCandidate;
            }
            parentCandidate.addAll(AggregationGroupScheduler.getOnTreeParents(child, agg));
        }

        return parentCandidate;
    }
}
