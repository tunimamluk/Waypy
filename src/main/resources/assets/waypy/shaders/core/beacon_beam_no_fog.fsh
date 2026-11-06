#version 330

// Vanilla core/rendertype_beacon_beam.fsh without apply_fog(): used for waypoint beams beyond the
// fog range, so they stay fully visible far past the render distance.

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0);
    color *= vertexColor * ColorModulator;
    fragColor = color;
}
