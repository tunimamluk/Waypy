package dev.waypy.render;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import dev.waypy.WaypyClient;

/**
 * Custom pipelines / render types for block highlights drawn <b>through</b> terrain.
 *
 * <p>They are vanilla's {@code DEBUG_FILLED_SNIPPET} (position_color quads, translucent) and
 * {@code LINES_SNIPPET} (screen-space-expanded lines) with the depth test replaced by
 * {@link CompareOp#ALWAYS_PASS} and depth writes off. The depth-tested variants simply use
 * vanilla's {@code RenderTypes.debugFilledBox()} / {@code RenderTypes.lines()}.
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
