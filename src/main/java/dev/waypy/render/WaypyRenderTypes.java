package dev.waypy.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import dev.waypy.WaypyClient;

/**
 * Custom pipelines / render types.
 *
 * <p>Block highlights drawn <b>through</b> terrain: these are vanilla's {@code DEBUG_FILLED_SNIPPET} (position_color quads, translucent) and
 * {@code LINES_SNIPPET} (screen-space-expanded lines) with the depth test replaced by
 * {@link CompareOp#ALWAYS_PASS} and depth writes off. The depth-tested variants simply use
 * vanilla's {@code RenderTypes.debugFilledBox()} / {@code RenderTypes.lines()}.
 *
 * <p>Fog-free beacon beams: vanilla's {@code BEACON_BEAM_OPAQUE} / {@code BEACON_BEAM_TRANSLUCENT}
 * (same snippet, blend and depth states) with the fragment shader swapped for
 * {@code waypy:core/beacon_beam_no_fog}, a copy of vanilla's beam fragment shader minus
 * {@code apply_fog}. Used for beams beyond the fog range; near beams use vanilla's types.
 */
final class WaypyRenderTypes {
	private static final DepthStencilState NO_DEPTH_TEST = new DepthStencilState(CompareOp.ALWAYS_PASS, false);

	static final RenderPipeline FILLED_BOX_NO_DEPTH_PIPELINE = RenderPipelines.register(
			RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
					.withLocation(id("pipeline/filled_box_no_depth"))
					.withDepthStencilState(NO_DEPTH_TEST)
					.build());

	static final RenderPipeline LINES_NO_DEPTH_PIPELINE = RenderPipelines.register(
			RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
					.withLocation(id("pipeline/lines_no_depth"))
					.withDepthStencilState(NO_DEPTH_TEST)
					.build());

	static final RenderPipeline BEACON_BEAM_NO_FOG_OPAQUE_PIPELINE = RenderPipelines.register(
			RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET)
					.withLocation(id("pipeline/beacon_beam_no_fog_opaque"))
					.withFragmentShader(id("core/beacon_beam_no_fog"))
					.build());

	static final RenderPipeline BEACON_BEAM_NO_FOG_TRANSLUCENT_PIPELINE = RenderPipelines.register(
			RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET)
					.withLocation(id("pipeline/beacon_beam_no_fog_translucent"))
					.withFragmentShader(id("core/beacon_beam_no_fog"))
					.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
					.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
					.build());

	/** Beam core without fog: like {@code RenderTypes.beaconBeam(BEAM_LOCATION, false)}. */
	static final RenderType BEACON_BEAM_NO_FOG_OPAQUE = RenderType.create("waypy_beacon_beam_no_fog",
			RenderSetup.builder(BEACON_BEAM_NO_FOG_OPAQUE_PIPELINE)
					.withTexture("Sampler0", BeaconRenderer.BEAM_LOCATION)
					.sortOnUpload()
					.createRenderSetup());

	/** Beam glow without fog: like {@code RenderTypes.beaconBeam(BEAM_LOCATION, true)}. */
	static final RenderType BEACON_BEAM_NO_FOG_TRANSLUCENT = RenderType.create("waypy_beacon_beam_no_fog",
			RenderSetup.builder(BEACON_BEAM_NO_FOG_TRANSLUCENT_PIPELINE)
					.withTexture("Sampler0", BeaconRenderer.BEAM_LOCATION)
					.sortOnUpload()
					.createRenderSetup());

	/** Translucent POSITION_COLOR quads, no depth test, sorted back-to-front on upload. */
	static final RenderType FILLED_BOX_NO_DEPTH = RenderType.create("waypy_filled_box_no_depth",
			RenderSetup.builder(FILLED_BOX_NO_DEPTH_PIPELINE).sortOnUpload().createRenderSetup());

	/** POSITION_COLOR_NORMAL_LINE_WIDTH lines, no depth test, drawn into the main target. */
	static final RenderType LINES_NO_DEPTH = RenderType.create("waypy_lines_no_depth",
			RenderSetup.builder(LINES_NO_DEPTH_PIPELINE).createRenderSetup());

	private WaypyRenderTypes() {
	}

	/** Forces class loading so the pipelines are registered before the shader manager preloads them. */
	static void init() {
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(WaypyClient.MOD_ID, path);
	}
}
