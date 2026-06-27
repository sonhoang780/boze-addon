package com.example.addon.render;

import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.util.Mth;

/**
 * TungTungSahur model — 128×128 texture atlas for high-resolution faces.
 * All cuboid dimensions are 2× the 64×64 version; render is compensated
 * with scale(-0.5, -0.5, 0.5) so visual size is identical.
 *
 * UV layout (128×128):
 *   Part        uv(u,v)    area       cuboid (dx×dy×dz)
 *   Head        ( 0,  0)  64×32      16×16×16
 *   Body        ( 0, 32)  56×36      16×24×12
 *   Right Arm   ( 0, 70)  16×32       4×28×4
 *   Left Arm    (20, 70)  16×32       4×28×4
 *   Right Leg   (40, 70)  24×26       6×20×6
 *   Left Leg    (68, 70)  24×26       6×20×6
 *   Stick       (64,  0)  24×62       6×56×6
 *
 * Model Y layout (in 128×128 model px, Y↓):
 *   y = −16 …   0 : head   (16 px)
 *   y =   0 …  24 : body   (24 px)
 *   y =  24 …  44 : legs   (20 px)   total = 44 px = 1.375 blocks at 1/32 scale
 * Shoulders at y=4, arm pivot x=±10.
 * translate(0, -2.75, 0) + scale(-0.5,-0.5,0.5) places feet at player.Y.
 */
public class TungTungModel {

    public final ModelPart head;
    public final ModelPart body;
    public final ModelPart rightArm;
    public final ModelPart leftArm;
    public final ModelPart rightLeg;
    public final ModelPart leftLeg;

    public TungTungModel(ModelPart root) {
        this.head     = root.getChild("head");
        this.body     = root.getChild("body");
        this.rightArm = root.getChild("right_arm");
        this.leftArm  = root.getChild("left_arm");
        this.rightLeg = root.getChild("right_leg");
        this.leftLeg  = root.getChild("left_leg");
    }

    public static LayerDefinition getTexturedModelData() {
        MeshDefinition  data = new MeshDefinition();
        PartDefinition root = data.getRoot();

        // HEAD — 16×16×16, pivot at neck (0,0,0), extends y=−16..0
        root.addOrReplaceChild("head",
            CubeListBuilder.create().texOffs(0, 0).addBox(-8f, -16f, -8f, 16f, 16f, 16f),
            PartPose.offset(0f, 0f, 0f));

        // BODY — 16×24×12 log trunk, hangs y=0..24
        root.addOrReplaceChild("body",
            CubeListBuilder.create().texOffs(0, 32).addBox(-8f, 0f, -6f, 16f, 24f, 12f),
            PartPose.offset(0f, 0f, 0f));

        // RIGHT ARM — 4×28×4 stick, shoulder pivot at (−10, 4, 0)
        PartDefinition rightArmData = root.addOrReplaceChild("right_arm",
            CubeListBuilder.create().texOffs(0, 70).addBox(-4f, 0f, -2f, 4f, 28f, 4f),
            PartPose.offset(-10f, 4f, 0f));

        // CLUB/STICK — 6×56×6, 90° roll so it extends sideways from the hand.
        // Pivot at (0, 28, 0) = end of arm; roll=π/2 rotates +Y → −X (outward for right arm).
        rightArmData.addOrReplaceChild("stick",
            CubeListBuilder.create().texOffs(64, 0).addBox(-3f, 0f, -3f, 6f, 56f, 6f),
            PartPose.offsetAndRotation(0f, 28f, 0f, -(float) Math.PI / 2f, 0f, 0f));

        // LEFT ARM — 4×28×4 stick, shoulder pivot at (10, 4, 0)
        root.addOrReplaceChild("left_arm",
            CubeListBuilder.create().texOffs(20, 70).addBox(0f, 0f, -2f, 4f, 28f, 4f),
            PartPose.offset(10f, 4f, 0f));

        // RIGHT LEG — 6×20×6, hip pivot at (−4, 24, 0)
        root.addOrReplaceChild("right_leg",
            CubeListBuilder.create().texOffs(40, 70).addBox(-3f, 0f, -3f, 6f, 20f, 6f),
            PartPose.offset(-4f, 24f, 0f));

        // LEFT LEG — 6×20×6, hip pivot at (4, 24, 0)
        root.addOrReplaceChild("left_leg",
            CubeListBuilder.create().texOffs(68, 70).addBox(-3f, 0f, -3f, 6f, 20f, 6f),
            PartPose.offset(4f, 24f, 0f));

        return LayerDefinition.create(data, 128, 128);
    }

    public void animate(float limbAngle, float limbDistance, float ageInTicks, boolean flying) {
        if (flying) {
            rightArm.xRot = -(float) Math.PI / 6f;
            rightArm.zRot  = -(float) Math.PI / 2.5f;
            leftArm.xRot  = -(float) Math.PI / 6f;
            leftArm.zRot   =  (float) Math.PI / 2.5f;
            rightLeg.xRot =  (float) Math.PI / 8f;
            leftLeg.xRot  =  (float) Math.PI / 8f;
            rightLeg.zRot  = 0f;
            leftLeg.zRot   = 0f;
            head.xRot = -0.15f;
            head.yRot   = 0f;
            return;
        }

        float walk = limbAngle * 0.6662f;

        rightArm.xRot = Mth.cos(walk + (float) Math.PI) * 1.8f * limbDistance * 0.7f;
        leftArm.xRot  = Mth.cos(walk)                   * 1.8f * limbDistance * 0.7f;

        rightLeg.xRot = Mth.cos(walk)                   * 1.3f * limbDistance;
        leftLeg.xRot  = Mth.cos(walk + (float) Math.PI) * 1.3f * limbDistance;

        // Arms hang straight at sides when idle (no roll)
        rightArm.zRot = 0f;
        leftArm.zRot  = 0f;
        rightLeg.zRot = 0f;
        leftLeg.zRot  = 0f;

        head.xRot = Mth.cos(ageInTicks * 0.10f) * 0.05f;
        head.yRot   = Mth.cos(ageInTicks * 0.07f) * 0.08f * (1f - limbDistance);
    }

    public void render(PoseStack matrices, VertexConsumer consumer, int light, int overlay) {
        head.render(matrices, consumer, light, overlay);
        body.render(matrices, consumer, light, overlay);
        rightArm.render(matrices, consumer, light, overlay);
        leftArm.render(matrices, consumer, light, overlay);
        rightLeg.render(matrices, consumer, light, overlay);
        leftLeg.render(matrices, consumer, light, overlay);
    }
}
