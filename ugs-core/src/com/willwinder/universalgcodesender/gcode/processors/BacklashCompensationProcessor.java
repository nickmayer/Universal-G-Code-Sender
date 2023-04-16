/*
    This file is part of Universal Gcode Sender (UGS).

    UGS is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    UGS is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with UGS.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.willwinder.universalgcodesender.gcode.processors;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.willwinder.universalgcodesender.gcode.GcodeParser;
import com.willwinder.universalgcodesender.gcode.GcodePreprocessorUtils;
import com.willwinder.universalgcodesender.gcode.GcodeState;
import com.willwinder.universalgcodesender.gcode.util.Code;
import com.willwinder.universalgcodesender.gcode.util.GcodeParserException;
import com.willwinder.universalgcodesender.gcode.util.GcodeParserUtils;
import com.willwinder.universalgcodesender.i18n.Localization;
import com.willwinder.universalgcodesender.model.Axis;
import com.willwinder.universalgcodesender.model.CNCPoint;
import com.willwinder.universalgcodesender.model.PartialPosition;
import com.willwinder.universalgcodesender.model.Position;

import java.lang.invoke.MethodHandles;
import java.text.DecimalFormat;
import java.util.EnumSet;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Insert moves to compensate for machine backlash
 */
public class BacklashCompensationProcessor implements CommandProcessor {
    private static final Logger LOGGER = Logger.getLogger(MethodHandles.lookup().lookupClass().getName());

    private final ImmutableMap<Axis, Double> backlash;
    private final ImmutableMap<Axis, Double> initialMove;
    private final double feedRate;
    private final EnumSet<Axis> supportedAxes = EnumSet.noneOf(Axis.class);

    private boolean initialized;
    private Position activeCompensation = new Position(Position.ZERO);


    public BacklashCompensationProcessor(double x, double y, double z, double initX, double initY, double initZ, double feedRate) {
        ImmutableMap.Builder<Axis, Double> backlashBuilder = new ImmutableMap.Builder<>();
        backlashBuilder.put(Axis.X, initX > 0 ? x : -x);
        backlashBuilder.put(Axis.Y, initY > 0 ? y : -y);
        backlashBuilder.put(Axis.Z, initZ > 0 ? z : -z);
        backlash = backlashBuilder.build();
        this.feedRate = feedRate;

        if (x != 0) {
            supportedAxes.add(Axis.X);
        }
        if (y != 0) {
            supportedAxes.add(Axis.Y);
        }
        if (z != 0) {
            supportedAxes.add(Axis.Z);
        }
        initialMove = ImmutableMap.of(
                Axis.X, initX,
                Axis.Y, initY,
                Axis.Z, initZ);

        reset();
    }

    @Override
    public void reset() {
        initialized = false;
    }

    @Override
    public List<String> processCommand(String commandStr, GcodeState state) throws GcodeParserException {
        List<GcodeParser.GcodeMeta> gcodeCommands = GcodeParserUtils.processCommand(commandStr, 0, state);
        if (!needsProcessing(gcodeCommands)) {
            return ImmutableList.of(commandStr);
        }

        try {
            ImmutableList.Builder<String> generatedCommands = ImmutableList.builder();

            String rewrittenCommandStr = commandStr;

            boolean lineRewritten = false;
            for (GcodeParser.GcodeMeta command : gcodeCommands) {
                if (command.code == Code.UNKNOWN) {
                    throw new GcodeParserException(
                            String.format(Localization.getString("backlash.error_processing_gcode"), commandStr));
                }

                // We need to inject the initialization move. This won't impact the current position as we'll move back
                // to the starting position, but it will ensure the last move is in the appropriate direction, so we
                // know the direction in which the backlash compensation needs to occur.
                if (!initialized) {
                    initialized = true;
                    DecimalFormat df = new DecimalFormat("0.####", Localization.dfs);

                    // Set the desired feed rate and relative movement mode
                    generatedCommands.add("G91").add("G94").add(String.format("G1 F%s", df.format(feedRate)));

                    // Do the first part of the Z move first (to ensure we move away from the work piece)
                    generatedCommands.add(String.format("G1 Z%s", df.format(initialMove.get(Axis.Z))));

                    // Do the X & Y moves together, and again negated
                    generatedCommands.add(String.format(
                            "G1 X%s Y%s",
                            df.format(initialMove.get(Axis.X)),
                            df.format(initialMove.get(Axis.Y))));
                    generatedCommands.add(String.format(
                            "G1 X%s Y%s",
                            df.format(-initialMove.get(Axis.X)),
                            df.format(-initialMove.get(Axis.Y))));

                    // Negate the Z move so that we're back at start, but with the last move known for all axes
                    generatedCommands.add(String.format("G1 Z%s", df.format(-initialMove.get(Axis.Z))));

                    // Reset the active compensation since we know all axes are in the zeroed position
                    activeCompensation = new Position(Position.ZERO);

                    // Restore settings we changed (motion mode, distance mode, & feed mode/rate)
                    generatedCommands.add(String.format(
                            "%s %s %s F%s",
                            command.state.currentMotionMode,
                            command.state.distanceMode,
                            command.state.feedMode,
                            df.format(command.state.speed)));
                }

                // This command doesn't have any destination location, so there is nothing to do.
                if (command.point == null) {
                    generatedCommands.add(command.code.toString());
                    continue;
                }

                // If the line has more than one move we can't rewrite it
                if (lineRewritten) {
                    throw new GcodeParserException(Localization.getString("parser.processor.general.multiple-commands"));
                }
                lineRewritten = true;

                Position start = new Position(state.currentPoint);
                Position end = new Position(command.point.point());
                LOGGER.log(Level.INFO,
                        () -> String.format(
                                "Process Move %s: %s -> %s  (%s)",
                                command.code,
                                state.currentPoint,
                                end, state.inAbsoluteMode ? "ABS" : "REL"));

                // Compute the movement direction for each axis. If the compensation doesn't match the active
                // compensation insert a backlash compensation move and update the active compensation.
                Position priorCompensation = new Position(activeCompensation);
                for (Axis axis : supportedAxes) {
                    double movement = end.get(axis) - start.get(axis);
                    if (movement == 0.0) {
                        continue;
                    }

                    if ((movement > 0 && initialMove.get(axis) >= 0) || (movement < 0 && initialMove.get(axis) < 0)) {
                        // Compensation is required for this move. If it isn't already in activeCompensation we need
                        // to add it.
                        double b = backlash.get(axis);
                        activeCompensation.set(axis, b);
                    } else {
                        // No compensation is required for this move. If there is compensation in activeCompensation we
                        // need to clear it.
                        activeCompensation.set(axis, 0.0);
                    }
                }

                if (command.state.inAbsoluteMode) {
                    Position originalCompensatedStart = new Position(start).add(priorCompensation);
                    start.add(activeCompensation);

                    // TODO: Make this extra move optional. For moves with no load (for example laser cutting) it isn't
                    //  important to eliminate the backlash first and it can be done over the entire move instead.
                    if (!priorCompensation.equals(activeCompensation)) {
                        LOGGER.log(Level.INFO, () -> String.format("Add backlash compensation of %s",
                                new Position(priorCompensation).sub(activeCompensation)));
                        generatedCommands.add(GcodePreprocessorUtils.generateLineFromPoints(
                                command.code, originalCompensatedStart, start, command.state.inAbsoluteMode));
                    }

                    // Adjust the end position to add in the active compensation. Axes with no adjustment needed are
                    // NaN.
                    PartialPosition.Builder endAdjustedPosition = PartialPosition.builder(end.getUnits());
                    if (!activeCompensation.equals(Position.ZERO) && command.state.inAbsoluteMode) {
                        LOGGER.log(Level.INFO, () -> String.format("Adjust by active %s", activeCompensation));
                        for (Axis a : Axis.values()) {
                            double adjustment = activeCompensation.get(a);
                            if (adjustment != 0) {
                                endAdjustedPosition.setValue(a, end.get(a) + adjustment);
                            }
                        }
                    }

                    rewrittenCommandStr = GcodePreprocessorUtils.overridePosition(
                            commandStr,
                            endAdjustedPosition.build());
                } else {
                    // Relative mode is easy, we'll just insert any changes to the active compensation before the
                    // original command
                    CNCPoint adjust = new Position(activeCompensation).sub(priorCompensation);
                    if (!adjust.equals(Position.ZERO)) {
                        LOGGER.log(Level.INFO, String.format("%s -> %s  (ABS)", Position.ZERO, adjust));
                        generatedCommands.add(GcodePreprocessorUtils.generateLineFromPoints(
                                command.code, Position.ZERO, adjust, false));
                    }
                }
            }

            generatedCommands.add(rewrittenCommandStr);
            return generatedCommands.build();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, commandStr, e);
            throw new GcodeParserException(
                    String.format(Localization.getString("backlash.error_processing_gcode"), commandStr));
        }
    }

    private boolean needsProcessing(List<GcodeParser.GcodeMeta> commands) throws GcodeParserException {
        if (commands == null) {
            return false;
        }
        boolean hasLine = false;
        for (GcodeParser.GcodeMeta command : commands) {
            switch(command.code) {
                case G0:
                case G1:
                    hasLine = true;
                    break;
                case G2:
                case G3:
                    throw new GcodeParserException(Localization.getString("backlash.unexpected_arc"));
            }
        }
        return hasLine;
    }


    @Override
    public String getHelp() {
        return "Insert moves to compensate for machine backlash";
    }
}
