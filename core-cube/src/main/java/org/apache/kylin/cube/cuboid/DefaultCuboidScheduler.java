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
import org.apache.kylin.cube.model.AggregationGroup.HierarchyMask;
import org.apache.kylin.cube.model.CubeDesc;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

public class DefaultCuboidScheduler extends CuboidScheduler {
    private final long max;
    private final Set<Long> allCuboidIds;
    private final Map<Long, List<Long>> parent2child;
    

    public DefaultCuboidScheduler(CubeDesc cubeDesc) {
        super(cubeDesc);
        
        int size = this.cubeDesc.getRowkey().getRowKeyColumns().length;
        this.max = (long) Math.pow(2, size) - 1;
        
        Pair<Set<Long>, Map<Long, List<Long>>> pair = buildTreeBottomUp();
        this.allCuboidIds = pair.getFirst();
        this.parent2child = pair.getSecond();
    }

    @Override
    public int getCuboidCount() {
        return allCuboidIds.size();
    }

    @Override
    public List<Long> getSpanningCuboid(long cuboid) {
        if (cuboid > max || cuboid < 0) {
            throw new IllegalArgumentException("Cuboid " + cuboid + " is out of scope 0-" + max);
        }

        List<Long> spanning = parent2child.get(cuboid);
        if (spanning == null) {
            return Collections.EMPTY_LIST;
        }
        return spanning;
    }

    @Override
    public Set<Long> getAllCuboidIds() {
        return Sets.newHashSet(allCuboidIds);
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
    private Pair<Set<Long>, Map<Long, List<Long>>> buildTreeBottomUp() {
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
     * Get the parent cuboid rely on the spanning tree.
     * @param cuboid an on-tree cuboid
     * @return
     */
    public long findBestMatchCuboid(long cuboid) {
        return findBestMatchCuboid1(cuboid);
    }

    public long findBestMatchCuboid2(long cuboid) {
        long bestParent = doFindBestMatchCuboid2(cuboid, Cuboid.getBaseCuboidId(cubeDesc));
        if (bestParent < -1) {
            throw new IllegalStateException("Cannot find the parent of the cuboid:" + cuboid);
        }
        return bestParent;
    }

    private long doFindBestMatchCuboid2(long cuboid, long parent) {
        if (!canDerive(cuboid, parent)) {
            return -1;
        }
        List<Long> children = parent2child.get(parent);
        List<Long> candidates = Lists.newArrayList();
        if (children != null) {
            for (long child : children) {
                long candidate = doFindBestMatchCuboid2(cuboid, child);
                if (candidate > 0) {
                    candidates.add(candidate);
                }
            }
        }
        if (candidates.isEmpty()) {
            candidates.add(parent);
        }

        return Collections.min(candidates, Cuboid.cuboidSelectComparator);
    }

    private boolean canDerive(long cuboidId, long parentCuboid) {
        return (cuboidId & ~parentCuboid) == 0;
    }

    public long findBestMatchCuboid1(long cuboid) {
        List<Long> onTreeCandidates = Lists.newArrayList();
        for (AggregationGroup agg : cubeDesc.getAggregationGroups()) {
            Long candidate = translateToOnTreeCuboid(agg, cuboid);
            if (candidate != null) {
                onTreeCandidates.add(candidate);
            }
        }

        if (onTreeCandidates.size() == 0) {
            return Cuboid.getBaseCuboidId(cubeDesc);
        }

        long onTreeCandi = Collections.min(onTreeCandidates, Cuboid.cuboidSelectComparator);
        if (allCuboidIds.contains(onTreeCandi)) {
            return onTreeCandi;
        }

        return doFindBestMatchCuboid1(onTreeCandi);
    }

    public long doFindBestMatchCuboid1(long cuboid) {
        long parent = getOnTreeParent(cuboid);
        while (parent > 0) {
            if (allCuboidIds.contains(parent)) {
                break;
            }
            parent = getOnTreeParent(parent);
        }

        if (parent <= 0) {
            throw new IllegalStateException("Can't find valid parent for Cuboid " + cuboid);
        }
        return parent;
    }

    private static Long translateToOnTreeCuboid(AggregationGroup agg, long cuboidID) {
        if ((cuboidID & ~agg.getPartialCubeFullMask()) > 0) {
            //the partial cube might not contain all required dims
            return null;
        }

        // add mandantory
        cuboidID = cuboidID | agg.getMandatoryColumnMask();

        // add hierarchy
        for (HierarchyMask hierarchyMask : agg.getHierarchyMasks()) {
            long fullMask = hierarchyMask.fullMask;
            long intersect = cuboidID & fullMask;
            if (intersect != 0 && intersect != fullMask) {

                boolean startToFill = false;
                for (int i = hierarchyMask.dims.length - 1; i >= 0; i--) {
                    if (startToFill) {
                        cuboidID |= hierarchyMask.dims[i];
                    } else {
                        if ((cuboidID & hierarchyMask.dims[i]) != 0) {
                            startToFill = true;
                            cuboidID |= hierarchyMask.dims[i];
                        }
                    }
                }
            }
        }

        // add joint dims
        for (Long joint : agg.getJoints()) {
            if (((cuboidID | joint) != cuboidID) && ((cuboidID & ~joint) != cuboidID)) {
                cuboidID = cuboidID | joint;
            }
        }

        if (!agg.isOnTree(cuboidID)) {
            // no column, add one column
            long nonJointDims = removeBits((agg.getPartialCubeFullMask() ^ agg.getMandatoryColumnMask()),
                    agg.getJoints());
            if (nonJointDims != 0) {
                long nonJointNonHierarchy = removeBits(nonJointDims,
                        Collections2.transform(agg.getHierarchyMasks(), new Function<HierarchyMask, Long>() {
                            @Override
                            public Long apply(HierarchyMask input) {
                                return input.fullMask;
                            }
                        }));
                if (nonJointNonHierarchy != 0) {
                    //there exists dim that does not belong to any joint or any hierarchy, that's perfect
                    return cuboidID | Long.lowestOneBit(nonJointNonHierarchy);
                } else {
                    //choose from a hierarchy that does not intersect with any joint dim, only check level 1
                    long allJointDims = agg.getJointDimsMask();
                    for (HierarchyMask hierarchyMask : agg.getHierarchyMasks()) {
                        long dim = hierarchyMask.allMasks[0];
                        if ((dim & allJointDims) == 0) {
                            return cuboidID | dim;
                        }
                    }
                }
            }

            cuboidID = cuboidID | Collections.min(agg.getJoints(), Cuboid.cuboidSelectComparator);
            Preconditions.checkState(agg.isOnTree(cuboidID));
        }
        return cuboidID;
    }

    private static long removeBits(long original, Collection<Long> toRemove) {
        long ret = original;
        for (Long joint : toRemove) {
            ret = ret & ~joint;
        }
        return ret;
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

    public String getResponsibleKey() {
        return CubeDesc.class.getName() + "-" + cubeDesc.getName();
    }
}