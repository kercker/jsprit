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
package jsprit.examples;

import jsprit.core.algorithm.VehicleRoutingAlgorithm;
import jsprit.core.algorithm.VehicleRoutingAlgorithmBuilder;
import jsprit.core.algorithm.selector.SelectBest;
import jsprit.core.algorithm.state.StateManager;
import jsprit.core.analysis.SolutionAnalyser;
import jsprit.core.problem.VehicleRoutingProblem;
import jsprit.core.problem.constraint.ConstraintManager;
import jsprit.core.problem.constraint.ServiceDeliveriesFirstConstraint;
import jsprit.core.problem.io.VrpXMLReader;
import jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import jsprit.core.problem.solution.route.VehicleRoute;
import jsprit.core.problem.solution.route.activity.TourActivity;
import jsprit.core.reporting.SolutionPrinter;
import jsprit.util.Examples;

import java.util.Collection;


public class VRPWithBackhaulsExample2 {
	
	public static void main(String[] args) {
		
		/*
		 * some preparation - create output folder
		 */
		Examples.createOutputFolder();
		
		/*
		 * Build the problem.
		 * 
		 * But define a problem-builder first.
		 */
		VehicleRoutingProblem.Builder vrpBuilder = VehicleRoutingProblem.Builder.newInstance();
		
		/*
		 * A solomonReader reads solomon-instance files, and stores the required information in the builder.
		 */
		new VrpXMLReader(vrpBuilder).read("input/pd_christophides_vrpnc1_vcap50.xml");
		

		/*
		 * Finally, the problem can be built. By default, transportCosts are crowFlyDistances (as usually used for vrp-instances).
		 */
		final VehicleRoutingProblem vrp = vrpBuilder.build();
		
//		new Plotter(vrp).plot("output/vrpwbh_christophides_vrpnc1.png", "pd_vrpnc1");
		
		
		/*
		 * Define the required vehicle-routing algorithms to solve the above problem.
		 * 
		 * The algorithm can be defined and configured in an xml-file.
		 */
//		VehicleRoutingAlgorithm vra = VehicleRoutingAlgorithms.readAndCreateAlgorithm(vrp, "input/algorithmConfig_solomon.xml");

        VehicleRoutingAlgorithmBuilder vraBuilder = new VehicleRoutingAlgorithmBuilder(vrp,"input/algorithmConfig_solomon.xml");
        vraBuilder.addDefaultCostCalculators();
        vraBuilder.addCoreConstraints();

        StateManager stateManager = new StateManager(vrp);
        ConstraintManager constraintManager = new ConstraintManager(vrp,stateManager);
        constraintManager.addConstraint(new ServiceDeliveriesFirstConstraint(), ConstraintManager.Priority.CRITICAL);

        vraBuilder.setStateAndConstraintManager(stateManager,constraintManager);
        VehicleRoutingAlgorithm vra = vraBuilder.build();



		/*
		 * Solve the problem.
		 * 
		 *
		 */
		Collection<VehicleRoutingProblemSolution> solutions = vra.searchSolutions();
		
		/*
		 * Retrieve best solution.
		 */
		VehicleRoutingProblemSolution solution = new SelectBest().selectSolution(solutions);
		
		/*
		 * print solution
		 */
		SolutionPrinter.print(solution);
		
		/*
		 * Plot solution. 
		 */
//		SolutionPlotter.plotSolutionAsPNG(vrp, solution, "output/pd_solomon_r101_solution.png","pd_r101");
//		Plotter plotter = new Plotter(vrp, solution);
//		plotter.setLabel(Label.SIZE);
//		plotter.plot("output/vrpwbh_christophides_vrpnc1_solution.png","vrpwbh_vrpnc1");

        SolutionAnalyser analyser = new SolutionAnalyser(vrp, solution, new SolutionAnalyser.DistanceCalculator() {

            @Override
            public double getDistance(String fromLocationId, String toLocationId) {
                return vrp.getTransportCosts().getTransportCost(fromLocationId,toLocationId,0.,null,null);
            }

        });

        for(VehicleRoute route : solution.getRoutes()){
            System.out.println("------");
            System.out.println("vehicleId: " + route.getVehicle().getId());
            System.out.println("vehicleCapacity: " + route.getVehicle().getType().getCapacityDimensions() + " maxLoad: " + analyser.getMaxLoad(route));
            System.out.println("totalDistance: " + analyser.getDistance(route));
            System.out.println("waitingTime: " + analyser.getWaitingTime(route));
            System.out.println("load@beginning: " + analyser.getLoadAtBeginning(route));
            System.out.println("load@end: " + analyser.getLoadAtEnd(route));
            System.out.println("operationTime: " + analyser.getOperationTime(route));
            System.out.println("serviceTime: " + analyser.getServiceTime(route));
            System.out.println("transportTime: " + analyser.getTransportTime(route));
            System.out.println("transportCosts: " + analyser.getVariableTransportCosts(route));
            System.out.println("fixedCosts: " + analyser.getFixedCosts(route));
            System.out.println("capViolationOnRoute: " + analyser.getCapacityViolation(route));
            System.out.println("capViolation@beginning: " + analyser.getCapacityViolationAtBeginning(route));
            System.out.println("capViolation@end: " + analyser.getCapacityViolationAtEnd(route));
            System.out.println("timeWindowViolationOnRoute: " + analyser.getTimeWindowViolation(route));
            System.out.println("skillConstraintViolatedOnRoute: " + analyser.hasSkillConstraintViolation(route));

            System.out.println("dist@" + route.getStart().getLocationId() + ": " + analyser.getDistanceAtActivity(route.getStart(),route));
            System.out.println("timeWindowViolation@"  + route.getStart().getLocationId() + ": " + analyser.getTimeWindowViolationAtActivity(route.getStart(), route));
            for(TourActivity act : route.getActivities()){
                System.out.println("--");
                System.out.println("actType: " + act.getName() + " demand: " + act.getSize());
                System.out.println("dist@" + act.getLocationId() + ": " + analyser.getDistanceAtActivity(act,route));
                System.out.println("load(before)@" + act.getLocationId() + ": " + analyser.getLoadJustBeforeActivity(act,route));
                System.out.println("load(after)@" + act.getLocationId() + ": " + analyser.getLoadRightAfterActivity(act, route));
                System.out.println("transportCosts@" + act.getLocationId() + ": " + analyser.getVariableTransportCostsAtActivity(act,route));
                System.out.println("capViolation(after)@" + act.getLocationId() + ": " + analyser.getCapacityViolationAfterActivity(act,route));
                System.out.println("timeWindowViolation@"  + act.getLocationId() + ": " + analyser.getTimeWindowViolationAtActivity(act,route));
                System.out.println("skillConstraintViolated@" + act.getLocationId() + ": " + analyser.hasSkillConstraintViolationAtActivity(act, route));
            }
            System.out.println("--");
            System.out.println("dist@" + route.getEnd().getLocationId() + ": " + analyser.getDistanceAtActivity(route.getEnd(),route));
            System.out.println("timeWindowViolation@"  + route.getEnd().getLocationId() + ": " + analyser.getTimeWindowViolationAtActivity(route.getEnd(),route));
        }

        System.out.println("-----");
        System.out.println("aggreate solution stats");
        System.out.println("total tp_distance: " + analyser.getDistance());
        System.out.println("total tp_time: " + analyser.getTransportTime());
        System.out.println("total waiting_time: " + analyser.getWaitingTime());
        System.out.println("total service_time: " + analyser.getServiceTime());
        System.out.println("total operation_time: " + analyser.getOperationTime());
        System.out.println("total twViolation: " + analyser.getTimeWindowViolation());
        System.out.println("total capViolation: " + analyser.getCapacityViolation());
        System.out.println("total fixedCosts: " + analyser.getFixedCosts());
        System.out.println("total variableCosts: " + analyser.getVariableTransportCosts());
        System.out.println("total costs: " + analyser.getTotalCosts());

    }

}
