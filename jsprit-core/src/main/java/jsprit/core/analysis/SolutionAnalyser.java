/*******************************************************************************
 * Copyright (C) 2014  Stefan Schroeder
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/

package jsprit.core.analysis;

import jsprit.core.algorithm.VariablePlusFixedSolutionCostCalculatorFactory;
import jsprit.core.algorithm.state.*;
import jsprit.core.problem.Capacity;
import jsprit.core.problem.VehicleRoutingProblem;
import jsprit.core.problem.solution.SolutionCostCalculator;
import jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import jsprit.core.problem.solution.route.VehicleRoute;
import jsprit.core.problem.solution.route.activity.*;
import jsprit.core.util.ActivityTimeTracker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Calculates a set of statistics for a solution.
 */
public class SolutionAnalyser {


    public static interface DistanceCalculator {

        public double getDistance(String fromLocationId, String toLocationId);

    }

    private static class BackhaulAndShipmentUpdater implements StateUpdater, ActivityVisitor {

        private final StateId backhaul_id;

        private final StateId shipment_id;

        private final StateManager stateManager;

        private Map<String,PickupShipment> openShipments;

        private VehicleRoute route;

        private Boolean shipmentConstraintOnRouteViolated;

        private Boolean backhaulConstraintOnRouteViolated;

        private boolean pickupOccured;

        private BackhaulAndShipmentUpdater(StateId backhaul_id, StateId shipment_id, StateManager stateManager) {
            this.stateManager = stateManager;
            this.backhaul_id = backhaul_id;
            this.shipment_id = shipment_id;
        }

        @Override
        public void begin(VehicleRoute route) {
            this.route = route;
            openShipments = new HashMap<String, PickupShipment>();
            pickupOccured = false;
            shipmentConstraintOnRouteViolated = false;
            backhaulConstraintOnRouteViolated = false;
        }

        @Override
        public void visit(TourActivity activity) {
            //shipment
            if(activity instanceof PickupShipment){
                openShipments.put(((PickupShipment) activity).getJob().getId(),(PickupShipment)activity);
            }
            else if(activity instanceof DeliverShipment){
                String jobId = ((DeliverShipment) activity).getJob().getId();
                if(!openShipments.containsKey(jobId)){
                    //deliverShipment without pickupShipment
                    stateManager.putActivityState(activity,shipment_id,true);
                    shipmentConstraintOnRouteViolated = true;
                }
                else {
                    PickupShipment removed = openShipments.remove(jobId);
                    stateManager.putActivityState(removed,shipment_id,false);
                    stateManager.putActivityState(activity,shipment_id,false);
                }
            }
            else stateManager.putActivityState(activity,shipment_id,false);

            //backhaul
            if(activity instanceof DeliverService && pickupOccured){
                stateManager.putActivityState(activity,backhaul_id,true);
                backhaulConstraintOnRouteViolated = true;
            }
            else{
                if(activity instanceof PickupService || activity instanceof ServiceActivity || activity instanceof PickupShipment){
                    pickupOccured = true;
                    stateManager.putActivityState(activity,backhaul_id,false);
                }
                else stateManager.putActivityState(activity,backhaul_id,false);
            }
        }

        @Override
        public void finish() {
            //shipment
            //pickups without deliveries
            for(TourActivity act : openShipments.values()){
                stateManager.putActivityState(act,shipment_id,true);
                shipmentConstraintOnRouteViolated = true;
            }
            stateManager.putRouteState(route,shipment_id,shipmentConstraintOnRouteViolated);
            //backhaul
            stateManager.putRouteState(route,backhaul_id,backhaulConstraintOnRouteViolated);
        }
    }

    private static class SumUpActivityTimes implements StateUpdater, ActivityVisitor {

        private StateId waiting_time_id;

        private StateId transport_time_id;

        private StateId service_time_id;

        private StateId too_late_id;

        private StateManager stateManager;

        private ActivityTimeTracker.ActivityPolicy activityPolicy;

        private VehicleRoute route;

        double sum_waiting_time = 0.;

        double sum_transport_time = 0.;

        double sum_service_time = 0.;

        double sum_too_late = 0.;

        double prevActDeparture;

        private SumUpActivityTimes(StateId waiting_time_id, StateId transport_time_id, StateId service_time_id, StateId too_late_id, StateManager stateManager, ActivityTimeTracker.ActivityPolicy activityPolicy) {
            this.waiting_time_id = waiting_time_id;
            this.transport_time_id = transport_time_id;
            this.service_time_id = service_time_id;
            this.too_late_id = too_late_id;
            this.stateManager = stateManager;
            this.activityPolicy = activityPolicy;
        }

        @Override
        public void begin(VehicleRoute route) {
            this.route = route;
            sum_waiting_time = 0.;
            sum_transport_time = 0.;
            sum_service_time = 0.;
            sum_too_late = 0.;
            prevActDeparture = route.getDepartureTime();
        }

        @Override
        public void visit(TourActivity activity) {
            //waiting time & toolate
            double waitAtAct = 0.;
            double tooLate = 0.;
            if(activityPolicy.equals(ActivityTimeTracker.ActivityPolicy.AS_SOON_AS_TIME_WINDOW_OPENS)){
                waitAtAct = Math.max(0,activity.getTheoreticalEarliestOperationStartTime() - activity.getArrTime());
                tooLate = Math.max(0,activity.getArrTime() - activity.getTheoreticalLatestOperationStartTime());
            }
            sum_waiting_time += waitAtAct;
            sum_too_late += tooLate;
            //transport time
            double transportTime = activity.getArrTime() - prevActDeparture;
            sum_transport_time += transportTime;
            prevActDeparture = activity.getEndTime();
            //service time
            sum_service_time += activity.getOperationTime();

        }

        @Override
        public void finish() {
            sum_transport_time += route.getEnd().getArrTime() - prevActDeparture;
            sum_too_late += Math.max(0, route.getEnd().getArrTime() - route.getEnd().getTheoreticalLatestOperationStartTime());
            stateManager.putRouteState(route,transport_time_id,sum_transport_time);
            stateManager.putRouteState(route,waiting_time_id,sum_waiting_time);
            stateManager.putRouteState(route,service_time_id,sum_service_time);
            stateManager.putRouteState(route,too_late_id,sum_too_late);
        }
    }

    private static class DistanceUpdater implements StateUpdater, ActivityVisitor {

        private StateId distance_id;

        private StateManager stateManager;

        private double sum_distance = 0.;

        private DistanceCalculator distanceCalculator;

        private TourActivity prevAct;

        private VehicleRoute route;

        private DistanceUpdater(StateId distance_id, StateManager stateManager, DistanceCalculator distanceCalculator) {
            this.distance_id = distance_id;
            this.stateManager = stateManager;
            this.distanceCalculator = distanceCalculator;
        }

        @Override
        public void begin(VehicleRoute route) {
            sum_distance = 0.;
            this.route = route;
            this.prevAct = route.getStart();
        }

        @Override
        public void visit(TourActivity activity) {
            double distance = distanceCalculator.getDistance(prevAct.getLocationId(),activity.getLocationId());
            sum_distance += distance;
            stateManager.putActivityState(activity,distance_id,sum_distance);
            prevAct = activity;
        }

        @Override
        public void finish() {
            double distance = distanceCalculator.getDistance(prevAct.getLocationId(), route.getEnd().getLocationId());
            sum_distance += distance;
            stateManager.putRouteState(route,distance_id,sum_distance);
        }
    }

    private static class SkillUpdater implements StateUpdater, ActivityVisitor {

        private StateManager stateManager;

        private StateId skill_id;

        private VehicleRoute route;

        private boolean skillConstraintViolatedOnRoute;

        private SkillUpdater(StateManager stateManager, StateId skill_id) {
            this.stateManager = stateManager;
            this.skill_id = skill_id;
        }

        @Override
        public void begin(VehicleRoute route) {
            this.route = route;
            skillConstraintViolatedOnRoute = false;
        }

        @Override
        public void visit(TourActivity activity) {
            boolean violatedAtActivity = false;
            if(activity instanceof TourActivity.JobActivity){
                Set<String> requiredForActivity = ((TourActivity.JobActivity) activity).getJob().getRequiredSkills().values();
                for(String skill : requiredForActivity){
                    if(!route.getVehicle().getSkills().containsSkill(skill)){
                        violatedAtActivity = true;
                        skillConstraintViolatedOnRoute = true;
                    }
                }
            }
            stateManager.putActivityState(activity,skill_id,violatedAtActivity);
        }

        @Override
        public void finish() {
            stateManager.putRouteState(route,skill_id, skillConstraintViolatedOnRoute);
        }
    }

    private static final Logger log = LogManager.getLogger(SolutionAnalyser.class);

    private VehicleRoutingProblem vrp;

    private StateManager stateManager;

    private DistanceCalculator distanceCalculator;

    private StateId waiting_time_id;

    private StateId transport_time_id;

    private StateId service_time_id;

    private StateId distance_id;

    private StateId too_late_id;

    private StateId shipment_id;

    private StateId backhaul_id;

    private StateId skill_id;

    private ActivityTimeTracker.ActivityPolicy activityPolicy;

    private final SolutionCostCalculator solutionCostCalculator;

    private Double tp_distance;
    private Double tp_time;
    private Double waiting_time;
    private Double service_time;
    private Double operation_time;
    private Double tw_violation;
    private Capacity cap_violation;
    private Double fixed_costs;
    private Double variable_transport_costs;
    private Boolean hasSkillConstraintViolation;
    private Boolean hasBackhaulConstraintViolation;
    private Boolean hasShipmentConstraintViolation;

    private Double total_costs;

    private VehicleRoutingProblemSolution solution;

    public SolutionAnalyser(VehicleRoutingProblem vrp, VehicleRoutingProblemSolution solution, DistanceCalculator distanceCalculator) {
        this.vrp = vrp;
        this.solution = solution;
        this.distanceCalculator = distanceCalculator;
        initialise();
        this.solutionCostCalculator = new VariablePlusFixedSolutionCostCalculatorFactory(stateManager).createCalculator();
        refreshStates();
    }

    public SolutionAnalyser(VehicleRoutingProblem vrp, VehicleRoutingProblemSolution solution, SolutionCostCalculator solutionCostCalculator, DistanceCalculator distanceCalculator) {
        this.vrp = vrp;
        this.solution = solution;
        this.distanceCalculator = distanceCalculator;
        this.solutionCostCalculator = solutionCostCalculator;
        initialise();
        refreshStates();
    }

    private void initialise() {
        this.stateManager = new StateManager(vrp);
        this.stateManager.updateTimeWindowStates();
        this.stateManager.updateLoadStates();
        this.stateManager.updateSkillStates();
        activityPolicy = ActivityTimeTracker.ActivityPolicy.AS_SOON_AS_TIME_WINDOW_OPENS;
        this.stateManager.addStateUpdater(new UpdateActivityTimes(vrp.getTransportCosts(),activityPolicy));
        this.stateManager.addStateUpdater(new UpdateVariableCosts(vrp.getActivityCosts(),vrp.getTransportCosts(),stateManager));
        waiting_time_id = stateManager.createStateId("waiting-time");
        transport_time_id = stateManager.createStateId("transport-time");
        service_time_id = stateManager.createStateId("service-time");
        distance_id = stateManager.createStateId("distance");
        too_late_id = stateManager.createStateId("too-late");
        shipment_id = stateManager.createStateId("shipment");
        backhaul_id = stateManager.createStateId("backhaul");
        skill_id = stateManager.createStateId("skills-violated");
        stateManager.addStateUpdater(new SumUpActivityTimes(waiting_time_id, transport_time_id, service_time_id,too_late_id , stateManager, activityPolicy));
        stateManager.addStateUpdater(new DistanceUpdater(distance_id,stateManager,distanceCalculator));
        stateManager.addStateUpdater(new BackhaulAndShipmentUpdater(backhaul_id,shipment_id,stateManager));
        stateManager.addStateUpdater(new SkillUpdater(stateManager,skill_id));
    }


    private void refreshStates(){
        stateManager.clear();
        stateManager.informInsertionStarts(solution.getRoutes(),null);
        clearSolutionIndicators();
        recalculateSolutionIndicators();
    }

    private void recalculateSolutionIndicators() {
        for(VehicleRoute route : solution.getRoutes()){
            tp_distance += getDistance(route);
            tp_time += getTransportTime(route);
            waiting_time += getWaitingTime(route);
            service_time += getServiceTime(route);
            operation_time += getOperationTime(route);
            tw_violation += getTimeWindowViolation(route);
            cap_violation = Capacity.addup(cap_violation,getCapacityViolation(route));
            fixed_costs += getFixedCosts(route);
            variable_transport_costs += getVariableTransportCosts(route);
            if(hasSkillConstraintViolation(route)) hasSkillConstraintViolation = true;
            if(hasShipmentConstraintViolation(route)) hasShipmentConstraintViolation = true;
            if(hasBackhaulConstraintViolation(route)) hasBackhaulConstraintViolation = true;
        }
        total_costs = solutionCostCalculator.getCosts(this.solution);
    }

    private void clearSolutionIndicators() {
        tp_distance = 0.;
        tp_time = 0.;
        waiting_time = 0.;
        service_time = 0.;
        operation_time = 0.;
        tw_violation = 0.;
        cap_violation = Capacity.Builder.newInstance().build();
        fixed_costs = 0.;
        variable_transport_costs = 0.;
        total_costs = 0.;
        hasBackhaulConstraintViolation = false;
        hasShipmentConstraintViolation = false;
        hasSkillConstraintViolation = false;
    }

    public void informSolutionChanged(VehicleRoutingProblemSolution newSolution){
        this.solution = newSolution;
        refreshStates();
    }

    /**
     * @param route to get the load at beginning from
     * @return load at start location of specified route
     */
    public Capacity getLoadAtBeginning(VehicleRoute route){
        if(route == null) throw new IllegalStateException("route is missing.");
        return stateManager.getRouteState(route, InternalStates.LOAD_AT_BEGINNING,Capacity.class);
    }

    /**
     * @param route to get the load at the end from
     * @return load at end location of specified route
     */
    public Capacity getLoadAtEnd(VehicleRoute route){
        if(route == null) throw new IllegalStateException("route is missing.");
        return stateManager.getRouteState(route, InternalStates.LOAD_AT_END,Capacity.class);
    }

    /**
     * @param route to get max load from
     * @return max load of specified route, i.e. for each capacity dimension the max value.
     */
    public Capacity getMaxLoad(VehicleRoute route){
        if(route == null) throw new IllegalStateException("route is missing.");
        return stateManager.getRouteState(route, InternalStates.MAXLOAD,Capacity.class);
    }

    /**
     *
     * @param activity to get the load from (after activity)
     * @return load right after the specified activity. If act is Start, it returns the load atBeginning of the specified
     * route. If act is End, it returns the load atEnd of specified route.
     * Returns null if no load can be found.
     */
    public Capacity getLoadRightAfterActivity(TourActivity activity, VehicleRoute route){
        if(route == null) throw new IllegalStateException("route is missing.");
        if(activity == null) throw new IllegalStateException("activity is missing.");
        if(activity instanceof Start) return getLoadAtBeginning(route);
        if(activity instanceof End) return getLoadAtEnd(route);
        verifyThatRouteContainsAct(activity, route);
        return stateManager.getActivityState(activity, InternalStates.LOAD, Capacity.class);
    }

    private void verifyThatRouteContainsAct(TourActivity activity, VehicleRoute route) {
        if(!route.getTourActivities().hasActivity(activity)){
            throw new IllegalStateException("specified route does not contain specified activity " + activity);
        }
    }

    /**
     * @param activity to get the load from (before activity)
     * @return load just before the specified activity. If act is Start, it returns the load atBeginning of the specified
     * route. If act is End, it returns the load atEnd of specified route.
     */
    public Capacity getLoadJustBeforeActivity(TourActivity activity, VehicleRoute route){
        if(route == null) throw new IllegalStateException("route is missing.");
        if(activity == null) throw new IllegalStateException("activity is missing.");
        if(activity instanceof Start) return getLoadAtBeginning(route);
        if(activity instanceof End) return getLoadAtEnd(route);
        verifyThatRouteContainsAct(activity, route);
        Capacity afterAct = stateManager.getActivityState(activity, InternalStates.LOAD,Capacity.class);
        if(afterAct != null && activity.getSize() != null){
            return Capacity.subtract(afterAct, activity.getSize());
        }
        else if(afterAct != null) return afterAct;
        else return null;
    }

    /**
     * @param route to get the capacity violation from
     * @return the capacity violation on this route, i.e. maxLoad - vehicleCapacity
     */
    public Capacity getCapacityViolation(VehicleRoute route){
        if(route == null) throw new IllegalStateException("route is missing.");
        Capacity maxLoad = getMaxLoad(route);
        return Capacity.max(Capacity.Builder.newInstance().build(),Capacity.subtract(maxLoad,route.getVehicle().getType().getCapacityDimensions()));
    }

    /**
     * @param route to get the capacity violation from (at beginning of the route)
     * @return violation, i.e. all dimensions and their corresponding violation. For example, if vehicle has two capacity
     * dimension with dimIndex=0 and dimIndex=1 and dimIndex=1 is violated by 4 units then this method returns
     * [[dimIndex=0][dimValue=0][dimIndex=1][dimValue=4]]
     */
    public Capacity getCapacityViolationAtBeginning(VehicleRoute route){
        if(route == null) throw new IllegalStateException("route is missing.");
        Capacity atBeginning = getLoadAtBeginning(route);
        return Capacity.max(Capacity.Builder.newInstance().build(),Capacity.subtract(atBeginning,route.getVehicle().getType().getCapacityDimensions()));
    }

    /**
     * @param route to get the capacity violation from (at end of the route)
     * @return violation, i.e. all dimensions and their corresponding violation. For example, if vehicle has two capacity
     * dimension with dimIndex=0 and dimIndex=1 and dimIndex=1 is violated by 4 units then this method returns
     * [[dimIndex=0][dimValue=0][dimIndex=1][dimValue=4]]
     */
    public Capacity getCapacityViolationAtEnd(VehicleRoute route){
        if(route == null) throw new IllegalStateException("route is missing.");
        Capacity atEnd = getLoadAtEnd(route);
        return Capacity.max(Capacity.Builder.newInstance().build(),Capacity.subtract(atEnd,route.getVehicle().getType().getCapacityDimensions()));
    }


    /**
     * @param route to get the capacity violation from (at activity of the route)
     * @return violation, i.e. all dimensions and their corresponding violation. For example, if vehicle has two capacity
     * dimension with dimIndex=0 and dimIndex=1 and dimIndex=1 is violated by 4 units then this method returns
     * [[dimIndex=0][dimValue=0][dimIndex=1][dimValue=4]]
     */
    public Capacity getCapacityViolationAfterActivity(TourActivity activity, VehicleRoute route){
        if(route == null) throw new IllegalStateException("route is missing.");
        if(activity == null) throw new IllegalStateException("activity is missing.");
        Capacity afterAct = getLoadRightAfterActivity(activity,route);
        return Capacity.max(Capacity.Builder.newInstance().build(),Capacity.subtract(afterAct,route.getVehicle().getType().getCapacityDimensions()));
    }

    /**
     * @param route to get the time window violation from
     * @return time violation of route, i.e. sum of individual activity time window violations.
     */
    public Double getTimeWindowViolation(VehicleRoute route){
        if(route == null) throw new IllegalStateException("route is missing.");
        return stateManager.getRouteState(route, too_late_id, Double.class);
    }

    /**
     * @param activity to get the time window violation from
     * @param route where activity needs to be part of
     * @return time violation of activity
     */
    public Double getTimeWindowViolationAtActivity(TourActivity activity, VehicleRoute route){
        if(route == null) throw new IllegalStateException("route is missing.");
        if(activity == null) throw new IllegalStateException("activity is missing.");
        return Math.max(0, activity.getArrTime() - activity.getTheoreticalLatestOperationStartTime());
    }

    /**
     * @param route to check skill constraint
     * @return true if skill constraint is violated, i.e. if vehicle does not have the required skills to conduct all
     * activities on the specified route. Returns null if route is null or skill state cannot be found.
     */
    public Boolean hasSkillConstraintViolation(VehicleRoute route){
        if(route == null) throw new IllegalStateException("route is missing.");
        return stateManager.getRouteState(route, skill_id, Boolean.class);
    }

    /**
     * @param activity to check skill constraint
     * @param route that must contain specified activity
     * @return true if vehicle does not have the skills to conduct specified activity, false otherwise. Returns null
     * if specified route or activity is null or if route does not contain specified activity or if skill state connot be
     * found. If specified activity is Start or End, it returns false.
     */
    public Boolean hasSkillConstraintViolationAtActivity(TourActivity activity, VehicleRoute route){
        if(route == null) throw new IllegalStateException("route is missing.");
        if(activity == null) throw new IllegalStateException("activity is missing.");
        if(activity instanceof Start) return false;
        if(activity instanceof End) return false;
        verifyThatRouteContainsAct(activity, route);
        return stateManager.getActivityState(activity, skill_id, Boolean.class);
    }

    /**
     * Returns true if backhaul constraint is violated (false otherwise). Backhaul constraint is violated if either a
     * pickupService, serviceActivity (which is basically modeled as pickupService) or a pickupShipment occur before
     * a deliverService activity or - to put it in other words - if a depot bounded delivery occurs after a pickup, thus
     * the backhaul ensures depot bounded delivery activities first.
     *
     * @param route to check backhaul constraint
     * @return true if backhaul constraint for specified route is violated. returns null if route is null or no backhaul
     * state can be found. In latter case try routeChanged(route).
     */
    public Boolean hasBackhaulConstraintViolation(VehicleRoute route){
        if(route == null) throw new IllegalStateException("route is missing.");
        return stateManager.getRouteState(route,backhaul_id,Boolean.class);
    }

    /**
     * @param activity to check backhaul violation
     * @param route that must contain the specified activity
     * @return true if backhaul constraint is violated, false otherwise. Null if either specified route or activity is null.
     * Null if specified route does not contain specified activity.
     */
    public Boolean hasBackhaulConstraintViolationAtActivity(TourActivity activity, VehicleRoute route){
        if(route == null) throw new IllegalStateException("route is missing.");
        if(activity == null) throw new IllegalStateException("activity is missing.");
        if(activity instanceof Start) return false;
        if(activity instanceof End) return false;
        verifyThatRouteContainsAct(activity, route);
        return stateManager.getActivityState(activity,backhaul_id,Boolean.class);
    }

    /**
     * Returns true, if shipment constraint is violated. Two activities are associated to a shipment: pickupShipment
     * and deliverShipment. If both shipments are not in the same route OR deliverShipment occurs before pickupShipment
     * then the shipment constraint is violated.
     *
     * @param route to check the shipment constraint.
     * @return true if violated, false otherwise. Null if no state can be found or specified route is null.
     */
    public Boolean hasShipmentConstraintViolation(VehicleRoute route){
        if(route == null) throw new IllegalStateException("route is missing.");
        return stateManager.getRouteState(route,shipment_id,Boolean.class);
    }

    /**
     * Returns true if shipment constraint is violated, i.e. if activity is deliverShipment but no pickupShipment can be
     * found before OR activity is pickupShipment and no deliverShipment can be found afterwards.
     *
     * @param activity to check the shipment constraint
     * @param route that must contain specified activity
     * @return true if shipment constraint is violated, false otherwise. If activity is either Start or End, it returns
     * false. Returns null if either specified activity or route is null or route does not containt activity.
     */
    public Boolean hasShipmentConstraintViolationAtActivity(TourActivity activity, VehicleRoute route){
        if(route == null) throw new IllegalStateException("route is missing.");
        if(activity == null) throw new IllegalStateException("activity is missing.");
        if(activity instanceof Start) return false;
        if(activity instanceof End) return false;
        verifyThatRouteContainsAct(activity, route);
        return stateManager.getActivityState(activity,shipment_id,Boolean.class);
    }



    /**
     * @param route to get the total operation time from
     * @return operation time of this route, i.e. endTime - startTime of specified route
     */
    public Double getOperationTime(VehicleRoute route){
        if(route == null) throw new IllegalStateException("route is missing.");
        return route.getEnd().getArrTime() - route.getStart().getEndTime();
    }

    /**
     * @param route to get the total waiting time from
     * @return total waiting time of this route, i.e. sum of waiting times at activities.
     * Returns null if no waiting time value exists for the specified route
     */
    public Double getWaitingTime(VehicleRoute route){
        if(route == null) throw new IllegalStateException("route is missing.");
        return stateManager.getRouteState(route, waiting_time_id, Double.class);
    }

    /**
     * @param route to get the total transport time from
     * @return total transport time of specified route. Returns null if no time value exists for the specified route.
     */
    public Double getTransportTime(VehicleRoute route){
        if(route == null) throw new IllegalStateException("route is missing.");
        return stateManager.getRouteState(route, transport_time_id, Double.class);
    }

    /**
     * @param route to get the total service time from
     * @return total service time of specified route. Returns null if no time value exists for specified route.
     */
    public Double getServiceTime(VehicleRoute route){
        if(route == null) throw new IllegalStateException("route is missing.");
        return stateManager.getRouteState(route, service_time_id, Double.class);
    }

    /**
     * @param route to get the transport costs from
     * @return total variable transport costs of route, i.e. sum of transport costs specified by
     * vrp.getTransportCosts().getTransportCost(fromId,toId,...)
     */
    public Double getVariableTransportCosts(VehicleRoute route){
        if(route == null) throw new IllegalStateException("route is missing.");

        return stateManager.getRouteState(route,InternalStates.COSTS,Double.class);
    }

    /**
     * @param route to get the fixed costs from
     * @return fixed costs of route, i.e. fixed costs of employed vehicle on this route.
     */
    public Double getFixedCosts(VehicleRoute route){
        if(route == null) throw new IllegalStateException("route is missing.");
        return route.getVehicle().getType().getVehicleCostParams().fix;
    }


    /**
     * @param activity to get the variable transport costs from
     * @param route where the activity should be part of
     * @return variable transport costs at activity, i.e. sum of transport costs from start of route to the specified activity
     * If activity is start, it returns 0.. If it is end, it returns .getVariableTransportCosts(route).
     */
    public Double getVariableTransportCostsAtActivity(TourActivity activity, VehicleRoute route){
        if(route == null) throw new IllegalStateException("route is missing.");
        if(activity == null) throw new IllegalStateException("activity is missing.");
        if(activity instanceof Start) return 0.;
        if(activity instanceof End) return getVariableTransportCosts(route);
        verifyThatRouteContainsAct(activity, route);
        return stateManager.getActivityState(activity,InternalStates.COSTS,Double.class);
    }

    /**
     * @param activity to get the waiting from
     * @param route where activity should be part of
     * @return waiting time at activity
     */
    public Double getWaitingTimeAtActivity(TourActivity activity, VehicleRoute route){
        if(route == null) throw new IllegalStateException("route is missing.");
        if(activity == null) throw new IllegalStateException("activity is missing.");
        double waitingTime = 0.;
        if(activityPolicy.equals(ActivityTimeTracker.ActivityPolicy.AS_SOON_AS_TIME_WINDOW_OPENS)){
            waitingTime = Math.max(0,activity.getTheoreticalEarliestOperationStartTime()-activity.getArrTime());
        }
        return waitingTime;
    }

    /**
     * @param route to get the distance from
     * @return total distance of route
     */
    public Double getDistance(VehicleRoute route){
        if(route == null) throw new IllegalStateException("route is missing.");
        return stateManager.getRouteState(route, distance_id, Double.class);
    }

    /**
     * @param activity at which is distance of the current route is measured
     * @return distance at activity
     */
    public Double getDistanceAtActivity(TourActivity activity, VehicleRoute route){
        if(route == null) throw new IllegalStateException("route is missing.");
        if(activity == null) throw new IllegalStateException("activity is missing.");
        if(activity instanceof Start) return 0.;
        if(activity instanceof End) return getDistance(route);
        verifyThatRouteContainsAct(activity, route);
        return stateManager.getActivityState(activity, distance_id, Double.class);
    }

    /**
     * @return total distance for specified solution
     */
    public Double getDistance(){
        return tp_distance;
    }

    /**
     * @return total operation time for specified solution
     */
    public Double getOperationTime(){
       return operation_time;
    }

    /**
     * @return total waiting time for specified solution
     */
    public Double getWaitingTime(){
        return waiting_time;
    }

    /**
     * @return total transportation time
     */
    public Double getTransportTime(){
        return tp_time;
    }

    /**
     * @return total time window violation for specified solution
     */
    public Double getTimeWindowViolation(){
        return tw_violation;
    }

    /**
     * @return total capacity violation for specified solution
     */
    public Capacity getCapacityViolation(){
        return cap_violation;
    }

    /**
     * @return total service time for specified solution
     */
    public Double getServiceTime(){
        return service_time;
    }

    /**
     * @return total fixed costs for specified solution
     */
    public Double getFixedCosts(){
        return fixed_costs;
    }

    /**
     * @return total variable transport costs for specified solution
     */
    public Double getVariableTransportCosts(){
        return variable_transport_costs;
    }

    /**
     * @return total costs defined by solutionCostCalculator
     */
    public Double getTotalCosts(){
        return total_costs;
    }

    /**
     * @return true if at least one route in specified solution has shipment constraint violation
     */
    public Boolean hasShipmentConstraintViolation(){
        return hasShipmentConstraintViolation;
    }

    /**
     * @return true if at least one route in specified solution has backhaul constraint violation
     */
    public Boolean hasBackhaulConstraintViolation(){
        return hasBackhaulConstraintViolation;
    }

    /**
     * @return true if at least one route in specified solution has skill constraint violation
     */
    public Boolean hasSkillConstraintViolation(){
        return hasSkillConstraintViolation;
    }







}

