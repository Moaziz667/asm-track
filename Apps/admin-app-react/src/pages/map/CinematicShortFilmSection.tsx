'use client';

import React, { useEffect, useRef, useMemo } from 'react';
import { Canvas, useFrame } from '@react-three/fiber';
import * as THREE from 'three';
import gsap from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';

if (typeof window !== 'undefined') {
  gsap.registerPlugin(ScrollTrigger);
}

// ── CONSTANTS & SUNNY DAYLIGHT PALETTES ─────────────────────────────────────
const COLOR_BG = '#FAF7F2'; // Warm cream blending into the landing page
const COLOR_AMBER = '#C4881A';
const COLOR_GREEN = '#16A34A';
const COLOR_SKY_BLUE = '#D2E6F1';
const COLOR_WOOD = '#8C6239';
const COLOR_SKIN = '#E5D5C5';
const COLOR_HAIR = '#2D1F17';

// Camera Waypoints along Z-axis for linear scroll narrative
const CAMERA_WAYPOINTS = [
  { p: 0.0, pos: [0.0, 1.45, -196.0], look: [0.0, 0.25, -200.0] }, // Scene 1: Dispatcher desk close-up
  { p: 0.2, pos: [0.0, 0.85, -97.0],  look: [0.0, 0.15, -100.0] }, // Scene 2: Smartphone notification
  { p: 0.4, pos: [0.28, 0.72, 3.4],   look: [0.0, 0.12, 0.0] },    // Scene 3: Cockpit steering prep
  { p: 0.6, pos: [3.4, 1.35, 103.8],  look: [0.0, 0.22, 100.0] },  // Scene 4: Highway transit side pan
  { p: 0.8, pos: [1.75, 1.15, 203.4], look: [0.0, 0.55, 200.0] },  // Scene 5: Doorstep handover
  { p: 1.0, pos: [0.0, 4.8, 206.5],   look: [0.0, 0.55, 200.0] },  // Cinematic ending aerial tilt
];

function interpolateCamera(progress: number) {
  let i = 0;
  for (; i < CAMERA_WAYPOINTS.length - 1; i++) {
    if (progress <= CAMERA_WAYPOINTS[i + 1].p) break;
  }
  const w1 = CAMERA_WAYPOINTS[i];
  const w2 = CAMERA_WAYPOINTS[i + 1];
  const t = (progress - w1.p) / (w2.p - w1.p);

  const px = w1.pos[0] + (w2.pos[0] - w1.pos[0]) * t;
  const py = w1.pos[1] + (w2.pos[1] - w1.pos[1]) * t;
  const pz = w1.pos[2] + (w2.pos[2] - w1.pos[2]) * t;

  const lx = w1.look[0] + (w2.look[0] - w1.look[0]) * t;
  const ly = w1.look[1] + (w2.look[1] - w1.look[1]) * t;
  const lz = w1.look[2] + (w2.look[2] - w1.look[2]) * t;

  return { pos: [px, py, pz], look: [lx, ly, lz] };
}

// ── HIGH-FIDELITY PROCEDURAL 3D HUMANOID CHARACTER ───────────────────────────
interface HumanoidProps {
  position: [number, number, number];
  pose?: 'neutral' | 'sitting' | 'holding-phone' | 'handing-package' | 'receiving-package' | 'driving';
  rotation?: [number, number, number];
  hairColor?: string;
  visorColor?: string;
  jacketColor?: string;
  hasCap?: boolean;
}

function StylizedHighFidelityHumanoid({
  position,
  pose = 'neutral',
  rotation = [0, 0, 0],
  hairColor = COLOR_HAIR,
  visorColor = COLOR_AMBER,
  jacketColor = '#1A1614',
  hasCap = false,
}: HumanoidProps) {
  const fingers = useMemo(() => [
    { name: 'thumb',  pos: [0.06, 0.0, 0.05] as [number, number, number], rot: [0, -Math.PI / 4, 0] as [number, number, number], len: 0.06 },
    { name: 'index',  pos: [0.04, 0.08, 0.08] as [number, number, number], rot: [-Math.PI / 8, 0, 0] as [number, number, number], len: 0.08 },
    { name: 'middle', pos: [0.015, 0.09, 0.085] as [number, number, number], rot: [-Math.PI / 7, 0, 0] as [number, number, number], len: 0.085 },
    { name: 'ring',   pos: [-0.01, 0.08, 0.08] as [number, number, number], rot: [-Math.PI / 6, 0, 0] as [number, number, number], len: 0.08 },
    { name: 'pinky',  pos: [-0.035, 0.06, 0.07] as [number, number, number], rot: [-Math.PI / 5, 0, 0] as [number, number, number], len: 0.065 },
  ], []);

  return (
    <group position={position} rotation={rotation}>
      {/* ── HEAD GROUP ── */}
      <group position={[0, 0.72, 0]}>
        <mesh castShadow>
          <sphereGeometry args={[0.2, 24, 24]} />
          <meshStandardMaterial color={COLOR_SKIN} roughness={0.7} />
        </mesh>
        <mesh position={[0, -0.22, 0]} castShadow>
          <cylinderGeometry args={[0.07, 0.08, 0.12, 12]} />
          <meshStandardMaterial color={COLOR_SKIN} roughness={0.7} />
        </mesh>
        {/* Anime Swept Spiky Hair */}
        <group position={[0, 0.02, -0.02]}>
          <mesh position={[0, 0, -0.04]} castShadow>
            <sphereGeometry args={[0.21, 16, 16, 0, Math.PI * 2, 0, Math.PI / 1.6]} />
            <meshStandardMaterial color={hairColor} roughness={0.88} />
          </mesh>
          {[
            { p: [0.08, 0.16, 0.12] as [number, number, number], r: [-0.3, 0.4, -0.3] as [number, number, number], s: [0.07, 0.18, 0.07] as [number, number, number] },
            { p: [-0.08, 0.16, 0.12] as [number, number, number], r: [-0.3, -0.4, 0.3] as [number, number, number], s: [0.07, 0.18, 0.07] as [number, number, number] },
            { p: [0.02, 0.20, 0.08] as [number, number, number], r: [-0.4, 0.1, -0.1] as [number, number, number], s: [0.08, 0.22, 0.08] as [number, number, number] },
            { p: [0.14, 0.06, 0.08] as [number, number, number], r: [0.1, 0.6, -0.4] as [number, number, number], s: [0.06, 0.14, 0.06] as [number, number, number] },
            { p: [-0.14, 0.06, 0.08] as [number, number, number], r: [0.1, -0.6, 0.4] as [number, number, number], s: [0.06, 0.14, 0.06] as [number, number, number] },
            { p: [0.0, 0.23, -0.06] as [number, number, number], r: [-0.6, 0, 0] as [number, number, number], s: [0.09, 0.25, 0.09] as [number, number, number] },
          ].map((spike, idx) => (
            <mesh key={idx} position={spike.p} rotation={spike.r} scale={spike.s} castShadow>
              <coneGeometry args={[1, 2, 4]} />
              <meshStandardMaterial color={hairColor} roughness={0.85} />
            </mesh>
          ))}
        </group>
        {/* Cyber Visor */}
        <group position={[0, 0.05, 0.12]} rotation={[0.08, 0, 0]}>
          <mesh castShadow>
            <boxGeometry args={[0.28, 0.07, 0.06]} />
            <meshStandardMaterial color={visorColor} roughness={0.1} metalness={0.9} emissive={visorColor} emissiveIntensity={0.8} transparent opacity={0.9} />
          </mesh>
          <mesh position={[0.15, 0, -0.08]} rotation={[0, Math.PI / 12, 0]} castShadow>
            <boxGeometry args={[0.03, 0.04, 0.18]} />
            <meshStandardMaterial color="#475569" metalness={0.8} />
          </mesh>
          <mesh position={[-0.15, 0, -0.08]} rotation={[0, -Math.PI / 12, 0]} castShadow>
            <boxGeometry args={[0.03, 0.04, 0.18]} />
            <meshStandardMaterial color="#475569" metalness={0.8} />
          </mesh>
        </group>
        {hasCap && (
          <group position={[0, 0.12, 0.02]} rotation={[-0.1, 0, 0]}>
            <mesh castShadow>
              <sphereGeometry args={[0.22, 16, 16, 0, Math.PI * 2, 0, Math.PI / 2]} />
              <meshStandardMaterial color={jacketColor} roughness={0.9} />
            </mesh>
            <mesh position={[0, 0.01, 0.16]} rotation={[0.05, 0, 0]} castShadow>
              <boxGeometry args={[0.28, 0.015, 0.18]} />
              <meshStandardMaterial color={jacketColor} roughness={0.9} />
            </mesh>
          </group>
        )}
      </group>

      {/* ── TORSO GROUP (COAT) ── */}
      <group position={[0, 0.18, 0]}>
        <mesh castShadow>
          <cylinderGeometry args={[0.22, 0.28, 0.8, 16]} />
          <meshStandardMaterial color={jacketColor} roughness={0.8} metalness={0.25} />
        </mesh>
        <mesh position={[0.1, 0.44, 0.1]} rotation={[0.2, 0.15, -Math.PI / 16]} castShadow>
          <boxGeometry args={[0.04, 0.16, 0.12]} />
          <meshStandardMaterial color={jacketColor} roughness={0.78} />
        </mesh>
        <mesh position={[-0.1, 0.44, 0.1]} rotation={[0.2, -0.15, Math.PI / 16]} castShadow>
          <boxGeometry args={[0.04, 0.16, 0.12]} />
          <meshStandardMaterial color={jacketColor} roughness={0.78} />
        </mesh>
        <mesh position={[0, 0.05, 0.245]} castShadow>
          <boxGeometry args={[0.015, 0.65, 0.01]} />
          <meshStandardMaterial color="#A37E2C" metalness={0.8} roughness={0.2} />
        </mesh>
      </group>

      {/* ── ARMS & HANDS ── */}
      <group
        position={
          pose === 'holding-phone'
            ? [0.28, 0.38, 0.18]
            : pose === 'handing-package' || pose === 'receiving-package'
            ? [0.26, 0.38, 0.24]
            : pose === 'driving'
            ? [0.28, 0.32, 0.25]
            : [0.28, 0.35, 0]
        }
        rotation={
          pose === 'holding-phone'
            ? [-Math.PI / 4.5, 0.1, -Math.PI / 5]
            : pose === 'handing-package' || pose === 'receiving-package'
            ? [-Math.PI / 3, 0, -Math.PI / 14]
            : pose === 'driving'
            ? [-Math.PI / 3.5, 0.2, -Math.PI / 8]
            : [0, 0, -0.05]
        }
      >
        <mesh castShadow>
          <cylinderGeometry args={[0.065, 0.065, 0.55, 8]} />
          <meshStandardMaterial color={jacketColor} roughness={0.8} />
        </mesh>
        <group position={[0, -0.32, 0]} rotation={[0.08, 0, 0]}>
          <mesh castShadow>
            <boxGeometry args={[0.08, 0.08, 0.038]} />
            <meshStandardMaterial color="#0E0C0B" roughness={0.9} />
          </mesh>
          {fingers.map((f) => (
            <mesh key={f.name} position={f.pos} rotation={f.rot} castShadow>
              <cylinderGeometry args={[0.009, 0.009, f.len, 6]} />
              <meshStandardMaterial color="#0E0C0B" roughness={0.9} />
            </mesh>
          ))}
        </group>
      </group>

      <group
        position={
          pose === 'handing-package' || pose === 'receiving-package'
            ? [-0.26, 0.38, 0.24]
            : pose === 'driving'
            ? [-0.28, 0.32, 0.25]
            : [-0.28, 0.35, 0]
        }
        rotation={
          pose === 'handing-package' || pose === 'receiving-package'
            ? [-Math.PI / 3, 0, Math.PI / 14]
            : pose === 'driving'
            ? [-Math.PI / 3.5, -0.2, Math.PI / 8]
            : [0, 0, 0.05]
        }
      >
        <mesh castShadow>
          <cylinderGeometry args={[0.065, 0.065, 0.55, 8]} />
          <meshStandardMaterial color={jacketColor} roughness={0.8} />
        </mesh>
        <group position={[0, -0.32, 0]} rotation={[0.08, 0, 0]}>
          <mesh castShadow>
            <boxGeometry args={[0.08, 0.08, 0.038]} />
            <meshStandardMaterial color="#0E0C0B" roughness={0.9} />
          </mesh>
          {fingers.map((f) => (
            <mesh key={f.name} position={[-f.pos[0], f.pos[1], f.pos[2]] as [number, number, number]} rotation={[f.rot[0], -f.rot[1], f.rot[2]] as [number, number, number]} castShadow>
              <cylinderGeometry args={[0.009, 0.009, f.len, 6]} />
              <meshStandardMaterial color="#0E0C0B" roughness={0.9} />
            </mesh>
          ))}
        </group>
      </group>

      {/* ── PANTS & BOOTS ── */}
      {pose === 'sitting' && (
        <group position={[0, -0.15, 0]}>
          <mesh position={[0.13, -0.12, 0.28]} rotation={[Math.PI / 2, 0, 0]} castShadow>
            <cylinderGeometry args={[0.09, 0.08, 0.55, 12]} />
            <meshStandardMaterial color="#100E0D" roughness={0.9} />
          </mesh>
          <mesh position={[0.13, -0.42, 0.54]} castShadow>
            <cylinderGeometry args={[0.08, 0.07, 0.45, 12]} />
            <meshStandardMaterial color="#100E0D" roughness={0.9} />
          </mesh>
          <group position={[0.13, -0.66, 0.56]} rotation={[0.05, 0, 0]}>
            <mesh castShadow>
              <boxGeometry args={[0.12, 0.08, 0.22]} />
              <meshStandardMaterial color="#070605" roughness={0.82} metalness={0.4} />
            </mesh>
          </group>

          <mesh position={[-0.13, -0.12, 0.28]} rotation={[Math.PI / 2, 0, 0]} castShadow>
            <cylinderGeometry args={[0.09, 0.08, 0.55, 12]} />
            <meshStandardMaterial color="#100E0D" roughness={0.9} />
          </mesh>
          <mesh position={[-0.13, -0.42, 0.54]} castShadow>
            <cylinderGeometry args={[0.08, 0.07, 0.45, 12]} />
            <meshStandardMaterial color="#100E0D" roughness={0.9} />
          </mesh>
          <group position={[-0.13, -0.66, 0.56]} rotation={[0.05, 0, 0]}>
            <mesh castShadow>
              <boxGeometry args={[0.12, 0.08, 0.22]} />
              <meshStandardMaterial color="#070605" roughness={0.82} metalness={0.4} />
            </mesh>
          </group>
        </group>
      )}

      {pose !== 'sitting' && (
        <group>
          <mesh position={[0.12, -0.38, 0]} castShadow>
            <cylinderGeometry args={[0.088, 0.075, 0.62, 12]} />
            <meshStandardMaterial color="#100E0D" roughness={0.9} />
          </mesh>
          <group position={[0.12, -0.74, 0.05]}>
            <mesh castShadow>
              <boxGeometry args={[0.11, 0.11, 0.22]} />
              <meshStandardMaterial color="#070605" roughness={0.85} metalness={0.35} />
            </mesh>
          </group>
          <mesh position={[-0.12, -0.38, 0]} castShadow>
            <cylinderGeometry args={[0.088, 0.075, 0.62, 12]} />
            <meshStandardMaterial color="#100E0D" roughness={0.9} />
          </mesh>
          <group position={[-0.12, -0.74, 0.05]}>
            <mesh castShadow>
              <boxGeometry args={[0.11, 0.11, 0.22]} />
              <meshStandardMaterial color="#070605" roughness={0.85} metalness={0.35} />
            </mesh>
          </group>
        </group>
      )}
    </group>
  );
}

// ── CUSTOM SCENE 01: DISPATCHER COMMAND CONSOLE (Z = -200) ───────────────────
function DispatcherWorkspaceScene() {
  const keysArray = useMemo(() => {
    const list = [];
    const rows = 5;
    const cols = 15;
    for (let r = 0; r < rows; r++) {
      for (let c = 0; c < cols; c++) {
        const x = (c - cols / 2) * 0.046;
        const z = (r - rows / 2) * 0.046;
        const colVal = (c + r) % 2 === 0 ? '#E2E8F0' : '#CBD5E1';
        list.push({ id: `${r}-${c}`, pos: [x, 0.012, z], color: colVal });
      }
    }
    return list;
  }, []);

  const holoSphereRef = useRef<THREE.Mesh>(null);
  const holoOrbit1Ref = useRef<THREE.Mesh>(null);
  const holoOrbit2Ref = useRef<THREE.Mesh>(null);

  useFrame((state) => {
    const time = state.clock.getElapsedTime();
    if (holoSphereRef.current) holoSphereRef.current.rotation.y = time * 0.3;
    if (holoOrbit1Ref.current) holoOrbit1Ref.current.rotation.z = -time * 0.8;
    if (holoOrbit2Ref.current) holoOrbit2Ref.current.rotation.x = time * 0.6;
  });

  return (
    <group position={[0, 0, -200]}>
      {/* Large modern oak command console desk */}
      <mesh position={[0, -0.4, -0.2]} castShadow receiveShadow>
        <boxGeometry args={[2.8, 0.08, 1.6]} />
        <meshStandardMaterial color={COLOR_WOOD} roughness={0.6} />
      </mesh>
      {/* White marble desk pad blotter */}
      <mesh position={[0, -0.355, 0.05]} castShadow receiveShadow>
        <boxGeometry args={[1.6, 0.01, 0.8]} />
        <meshStandardMaterial color="#FAF9F6" roughness={0.1} />
      </mesh>
      {/* Sleek drawer cabinets */}
      <mesh position={[1.2, -0.85, -0.2]} castShadow receiveShadow>
        <boxGeometry args={[0.3, 0.9, 1.4]} />
        <meshStandardMaterial color="#E2E8F0" roughness={0.4} />
      </mesh>
      <mesh position={[-1.2, -0.85, -0.2]} castShadow receiveShadow>
        <boxGeometry args={[0.3, 0.9, 1.4]} />
        <meshStandardMaterial color="#E2E8F0" roughness={0.4} />
      </mesh>

      {/* High-Fidelity Keyboard */}
      <group position={[0, -0.34, 0.2]}>
        <mesh castShadow>
          <boxGeometry args={[0.78, 0.024, 0.28]} />
          <meshStandardMaterial color="#F8FAFC" roughness={0.5} />
        </mesh>
        {keysArray.map((k) => (
          <mesh key={k.id} position={k.pos as [number, number, number]} castShadow>
            <boxGeometry args={[0.038, 0.024, 0.038]} />
            <meshStandardMaterial color={k.color} roughness={0.3} />
          </mesh>
        ))}
      </group>

      {/* Ceramic Coffee Mug */}
      <group position={[-0.55, -0.33, 0.0]}>
        <mesh castShadow>
          <cylinderGeometry args={[0.07, 0.07, 0.16, 12]} />
          <meshStandardMaterial color={COLOR_AMBER} roughness={0.1} />
        </mesh>
        <mesh position={[0.07, 0, 0]} rotation={[0, 0, Math.PI / 2]}>
          <torusGeometry args={[0.045, 0.012, 8, 16, Math.PI]} />
          <meshStandardMaterial color={COLOR_AMBER} />
        </mesh>
      </group>

      {/* 3D Holographic Route Globe Floating Above Console */}
      <group position={[0.65, 0.15, 0.0]}>
        <mesh position={[0, -0.48, 0]} castShadow>
          <cylinderGeometry args={[0.26, 0.28, 0.04, 16]} />
          <meshStandardMaterial color="#E2E8F0" metalness={0.7} />
        </mesh>
        <mesh position={[0, -0.45, 0]}>
          <cylinderGeometry args={[0.2, 0.2, 0.02, 16]} />
          <meshBasicMaterial color={COLOR_SKY_BLUE} toneMapped={false} />
        </mesh>
        {/* Hologram Helmet (procedural visual mesh) */}
        <group ref={holoSphereRef}>
          <mesh position={[0, 0, 0]} castShadow>
            <boxGeometry args={[0.22, 0.18, 0.24]} />
            <meshStandardMaterial color={COLOR_AMBER} roughness={0.2} metalness={0.8} />
          </mesh>
          <mesh position={[0, 0.04, 0.121]} castShadow>
            <boxGeometry args={[0.18, 0.08, 0.02]} />
            <meshBasicMaterial color={COLOR_SKY_BLUE} />
          </mesh>
        </group>
        <mesh ref={holoOrbit1Ref} position={[0, 0, 0]}>
          <torusGeometry args={[0.3, 0.008, 8, 32]} />
          <meshBasicMaterial color={COLOR_AMBER} toneMapped={false} transparent opacity={0.5} />
        </mesh>
        <mesh ref={holoOrbit2Ref} position={[0, 0, 0]} rotation={[Math.PI / 4, 0, 0]}>
          <torusGeometry args={[0.34, 0.008, 8, 32]} />
          <meshBasicMaterial color={COLOR_GREEN} toneMapped={false} transparent opacity={0.4} />
        </mesh>
      </group>

      {/* Modern Curved Task Chair */}
      <group position={[0, -0.65, 0.85]}>
        {/* Headrest */}
        <mesh position={[0, 0.72, -0.1]} castShadow>
          <boxGeometry args={[0.42, 0.18, 0.08]} />
          <meshStandardMaterial color="#0D0C0B" roughness={0.9} />
        </mesh>
        {/* Seat cushion */}
        <mesh position={[0, 0.02, 0.12]} castShadow>
          <boxGeometry args={[0.55, 0.07, 0.52]} />
          <meshStandardMaterial color={COLOR_AMBER} roughness={0.6} />
        </mesh>
        <mesh position={[0, -0.28, 0]} castShadow>
          <cylinderGeometry args={[0.038, 0.038, 0.42, 8]} />
          <meshStandardMaterial color="#E2E8F0" metalness={0.8} />
        </mesh>
      </group>

      {/* Curved Display Screens */}
      <group position={[0, 0.28, -0.5]}>
        <group position={[-0.45, 0, 0.08]} rotation={[0, Math.PI / 10, 0]}>
          <mesh castShadow>
            <boxGeometry args={[0.88, 0.52, 0.04]} />
            <meshStandardMaterial color="#1E293B" roughness={0.4} />
          </mesh>
          <mesh position={[0, 0, 0.021]}>
            <planeGeometry args={[0.84, 0.48]} />
            <meshBasicMaterial color={COLOR_SKY_BLUE} toneMapped={false} />
          </mesh>
        </group>
        <group position={[0.45, 0, 0.08]} rotation={[0, -Math.PI / 10, 0]}>
          <mesh castShadow>
            <boxGeometry args={[0.88, 0.52, 0.04]} />
            <meshStandardMaterial color="#1E293B" roughness={0.4} />
          </mesh>
          <mesh position={[0, 0, 0.021]}>
            <planeGeometry args={[0.84, 0.48]} />
            <meshBasicMaterial color={COLOR_SKY_BLUE} toneMapped={false} />
          </mesh>
        </group>
        <mesh position={[0, -0.4, -0.1]} castShadow>
          <cylinderGeometry args={[0.042, 0.042, 0.65, 8]} />
          <meshStandardMaterial color="#CBD5E1" metalness={0.9} />
        </mesh>
      </group>

      {/* Styled Dispatcher sitting */}
      <StylizedHighFidelityHumanoid position={[0, -0.22, 0.42]} pose="sitting" hairColor="#4A3728" visorColor={COLOR_SKY_BLUE} jacketColor="#1E3A8A" />

      {/* Sunny spotlights */}
      <spotLight
        position={[0, 3.8, 0.2]}
        angle={0.68}
        penumbra={0.4}
        intensity={4.2}
        color="#FFFDF5"
        castShadow
      />
    </group>
  );
}

// ── CUSTOM SCENE 02: HIGH-FIDELITY SMARTPHONE VIBRATION (Z = -100) ───────────
function SmartphoneVibrationScene({
  stateRef,
}: {
  stateRef: React.MutableRefObject<{ scrollProgress: number }>;
}) {
  const phoneRef = useRef<THREE.Group>(null);
  const ringPulse1Ref = useRef<THREE.Mesh>(null);
  const ringPulse2Ref = useRef<THREE.Mesh>(null);

  useFrame((state) => {
    const scrollProgress = stateRef.current.scrollProgress;
    const time = state.clock.getElapsedTime();
    if (!phoneRef.current) return;

    if (scrollProgress >= 0.2 && scrollProgress <= 0.4) {
      const shakeFactor = 0.024;
      phoneRef.current.position.x = Math.sin(time * 62) * shakeFactor;
      phoneRef.current.position.y = Math.cos(time * 72) * shakeFactor;
    } else {
      phoneRef.current.position.set(0, 0, 0);
    }

    if (ringPulse1Ref.current && ringPulse2Ref.current) {
      const scale1 = 1.0 + ((time * 0.8) % 1.5);
      const scale2 = 1.0 + (((time + 0.5) * 0.8) % 1.5);
      ringPulse1Ref.current.scale.set(scale1, scale1, 1);
      ringPulse2Ref.current.scale.set(scale2, scale2, 1);

      const mat1 = ringPulse1Ref.current.material as THREE.Material;
      const mat2 = ringPulse2Ref.current.material as THREE.Material;
      mat1.opacity = Math.max(0, 1 - (scale1 - 1) / 1.5);
      mat2.opacity = Math.max(0, 1 - (scale2 - 1) / 1.5);
    }
  });

  return (
    <group position={[0, 0.12, -100]}>
      <group position={[0, 0, -0.4]}>
        <mesh ref={ringPulse1Ref}>
          <ringGeometry args={[0.62, 0.63, 32]} />
          <meshBasicMaterial color={COLOR_AMBER} transparent opacity={0.5} toneMapped={false} />
        </mesh>
        <mesh ref={ringPulse2Ref}>
          <ringGeometry args={[0.62, 0.63, 32]} />
          <meshBasicMaterial color={COLOR_SKY_BLUE} transparent opacity={0.3} toneMapped={false} />
        </mesh>
      </group>

      <group ref={phoneRef}>
        {/* Sleeve cuffs */}
        <mesh position={[0.48, -0.22, 0.28]} rotation={[0, -Math.PI / 8, 0]} castShadow>
          <cylinderGeometry args={[0.1, 0.12, 0.42, 12]} />
          <meshStandardMaterial color="#475569" roughness={0.7} />
        </mesh>
        <mesh position={[0.48, -0.22, 0.28]} castShadow>
          <torusGeometry args={[0.102, 0.015, 8, 16]} />
          <meshStandardMaterial color="#475569" />
        </mesh>

        {/* Hand holding phone */}
        {[
          { p: [0.38, -0.05, 0.12] as [number, number, number], r: [0, -Math.PI / 6, 0.05] as [number, number, number], s: [0.06, 0.22, 0.08] as [number, number, number] },
          { p: [0.39, 0.12, 0.08] as [number, number, number], r: [0.1, -Math.PI / 6, 0.08] as [number, number, number], s: [0.05, 0.18, 0.08] as [number, number, number] },
          { p: [0.38, -0.22, 0.15] as [number, number, number], r: [-0.1, -Math.PI / 6, 0.02] as [number, number, number], s: [0.055, 0.18, 0.08] as [number, number, number] },
        ].map((f, i) => (
          <mesh key={i} position={f.p} rotation={f.r} scale={f.s} castShadow>
            <boxGeometry args={[1, 1, 1]} />
            <meshStandardMaterial color="#F3E8EE" roughness={0.6} />
          </mesh>
        ))}

        {/* Bevelled Smartphone */}
        <group rotation={[0.04, 0, 0.02]}>
          <mesh castShadow>
            <boxGeometry args={[0.74, 1.48, 0.08]} />
            <meshStandardMaterial color="#64748B" roughness={0.3} metalness={0.8} />
          </mesh>
          <mesh position={[0.375, 0.18, 0]}>
            <boxGeometry args={[0.01, 0.14, 0.03]} />
            <meshStandardMaterial color="#A37E2C" metalness={0.9} />
          </mesh>
          <mesh position={[0, 0, 0.042]}>
            <planeGeometry args={[0.66, 1.38]} />
            <meshBasicMaterial color={COLOR_AMBER} toneMapped={false} />
          </mesh>
          <group position={[0, 0.1, 0.08]}>
            <mesh castShadow>
              <boxGeometry args={[0.58, 0.32, 0.03]} />
              <meshStandardMaterial color="#FAF9F6" roughness={0.3} />
            </mesh>
            <mesh position={[0, 0, 0.016]}>
              <planeGeometry args={[0.54, 0.28]} />
              <meshBasicMaterial color="#000000" />
            </mesh>
            <mesh position={[0, -0.06, 0.018]}>
              <planeGeometry args={[0.42, 0.08]} />
              <meshBasicMaterial color={COLOR_GREEN} toneMapped={false} />
            </mesh>
          </group>
        </group>
      </group>

      <pointLight position={[0, 0.25, 0.8]} intensity={2.2} color={COLOR_AMBER} distance={3.8} />
    </group>
  );
}

// ── CUSTOM SCENE 03: VEHICLE CAB COCKPIT PREP (Z = 0) ────────────────────────
function VehicleCockpitPrepScene() {
  return (
    <group position={[0, 0, 0]}>
      {/* Dashboard console */}
      <mesh position={[0, -0.52, -0.9]} castShadow receiveShadow>
        <boxGeometry args={[2.5, 0.65, 1.2]} />
        <meshStandardMaterial color="#334155" roughness={0.8} />
      </mesh>

      {/* Recessed cockpit gauge meters */}
      <group position={[-0.32, -0.22, -0.42]}>
        <mesh castShadow>
          <boxGeometry args={[0.48, 0.22, 0.03]} />
          <meshStandardMaterial color="#1E293B" roughness={0.9} />
        </mesh>
        <mesh position={[-0.12, 0, 0.016]}>
          <torusGeometry args={[0.075, 0.006, 8, 24]} />
          <meshBasicMaterial color={COLOR_AMBER} toneMapped={false} />
        </mesh>
        <mesh position={[0.12, 0, 0.016]}>
          <torusGeometry args={[0.075, 0.006, 8, 24]} />
          <meshBasicMaterial color={COLOR_GREEN} toneMapped={false} />
        </mesh>
      </group>

      {/* Steering Wheel */}
      <group position={[-0.32, -0.15, -0.36]} rotation={[-Math.PI / 5, 0, 0]}>
        <mesh castShadow>
          <torusGeometry args={[0.32, 0.035, 12, 48]} />
          <meshStandardMaterial color="#0F172A" roughness={0.9} />
        </mesh>
        <mesh position={[0, 0, 0]} castShadow>
          <boxGeometry args={[0.55, 0.04, 0.02]} />
          <meshStandardMaterial color="#94A3B8" metalness={0.8} />
        </mesh>
        <mesh position={[0, 0, 0.01]} castShadow>
          <cylinderGeometry args={[0.07, 0.07, 0.02, 16]} />
          <meshStandardMaterial color="#0F172A" metalness={0.7} />
        </mesh>
        <mesh position={[0, 0, -0.24]} rotation={[Math.PI / 2, 0, 0]} castShadow>
          <cylinderGeometry args={[0.036, 0.036, 0.52, 8]} />
          <meshStandardMaterial color="#475569" />
        </mesh>
      </group>

      {/* Mounted dashboard phone navigation GPS */}
      <group position={[0.22, 0.06, -0.48]} rotation={[0, -Math.PI / 9, 0]}>
        <mesh castShadow>
          <boxGeometry args={[0.18, 0.35, 0.04]} />
          <meshStandardMaterial color="#475569" metalness={0.65} />
        </mesh>
        <mesh position={[0, 0, 0.021]}>
          <planeGeometry args={[0.15, 0.31]} />
          <meshBasicMaterial color="#0F172A" />
        </mesh>
        <mesh position={[0, 0, 0.024]}>
          <planeGeometry args={[0.015, 0.2]} />
          <meshBasicMaterial color={COLOR_GREEN} toneMapped={false} />
        </mesh>
        <mesh position={[0, 0.02, 0.026]} rotation={[0, 0, 0]}>
          <coneGeometry args={[0.022, 0.045, 3]} />
          <meshBasicMaterial color={COLOR_SKY_BLUE} toneMapped={false} />
        </mesh>
      </group>

      {/* Driver character sitting gripping steering wheel */}
      <StylizedHighFidelityHumanoid position={[-0.32, -0.25, 0.28]} pose="driving" hasCap />

      <pointLight position={[-0.3, -0.25, -0.22]} intensity={1.8} color={COLOR_AMBER} distance={2.4} />
      <pointLight position={[0.2, 0.0, -0.32]} intensity={1.2} color={COLOR_GREEN} distance={1.8} />
    </group>
  );
}

// ── CUSTOM SCENE 04: TRANSIT PARALLAX HIGHWAY (Z = 100) ──────────────────────
function CityTransitVanScene({
  stateRef,
}: {
  stateRef: React.MutableRefObject<{ scrollProgress: number }>;
}) {
  const vanGroupRef = useRef<THREE.Group>(null);
  const cityGroupRef = useRef<THREE.Group>(null);
  const wheelLRef = useRef<THREE.Mesh>(null);
  const wheelRRef = useRef<THREE.Mesh>(null);

  const buildings = useMemo(() => {
    const arr = [];
    for (let i = 0; i < 15; i++) {
      const h = 5.2 + Math.random() * 5.8;
      const w = 1.6 + Math.random() * 0.8;
      const xOffset = i % 2 === 0 ? -5.5 - Math.random() * 2.0 : 5.5 + Math.random() * 2.0;
      const zOffset = -25.0 + i * 4.2;
      arr.push({ id: i, h, w, x: xOffset, z: zOffset });
    }
    return arr;
  }, []);

  useFrame((state) => {
    const scrollProgress = stateRef.current.scrollProgress;
    const time = state.clock.getElapsedTime();
    const spinSpeed = 9.8;

    if (wheelLRef.current && wheelRRef.current) {
      wheelLRef.current.rotation.x = time * spinSpeed;
      wheelRRef.current.rotation.x = time * spinSpeed;
    }

    if (cityGroupRef.current) {
      cityGroupRef.current.position.z = -((time * 4.6) % 4.2);
    }

    if (vanGroupRef.current) {
      if (scrollProgress >= 0.6 && scrollProgress <= 0.8) {
        const factor = (scrollProgress - 0.6) / 0.2;
        vanGroupRef.current.position.x = -1.35 + factor * 1.55;
      } else {
        vanGroupRef.current.position.x = -1.35;
      }
    }
  });

  return (
    <group position={[0, 0, 100]}>
      {/* Light gray concrete highway bed */}
      <mesh rotation={[-Math.PI / 2, 0, 0]} receiveShadow>
        <planeGeometry args={[14, 60]} />
        <meshStandardMaterial color="#E2E8F0" roughness={0.7} />
      </mesh>

      {/* Double Solid Center Lines */}
      <mesh position={[0, 0.005, 0]} rotation={[-Math.PI / 2, 0, 0]}>
        <planeGeometry args={[0.04, 60]} />
        <meshBasicMaterial color={COLOR_AMBER} />
      </mesh>
      <mesh position={[0.08, 0.005, 0]} rotation={[-Math.PI / 2, 0, 0]}>
        <planeGeometry args={[0.04, 60]} />
        <meshBasicMaterial color={COLOR_AMBER} />
      </mesh>

      {/* Highway Guardrail Left */}
      <group position={[-4.5, 0, 0]}>
        <mesh position={[0, 0.35, 0]} castShadow>
          <boxGeometry args={[0.08, 0.12, 60]} />
          <meshStandardMaterial color="#94A3B8" metalness={0.8} />
        </mesh>
        {[...Array(12)].map((_, idx) => (
          <mesh key={idx} position={[0, 0.15, -24 + idx * 5] as [number, number, number]} castShadow>
            <boxGeometry args={[0.08, 0.3, 0.08]} />
            <meshStandardMaterial color="#64748B" metalness={0.7} />
          </mesh>
        ))}
      </group>

      {/* Highway Guardrail Right */}
      <group position={[4.5, 0, 0]}>
        <mesh position={[0, 0.35, 0]} castShadow>
          <boxGeometry args={[0.08, 0.12, 60]} />
          <meshStandardMaterial color="#94A3B8" metalness={0.8} />
        </mesh>
        {[...Array(12)].map((_, idx) => (
          <mesh key={idx} position={[0, 0.15, -24 + idx * 5] as [number, number, number]} castShadow>
            <boxGeometry args={[0.08, 0.3, 0.08]} />
            <meshStandardMaterial color="#64748B" metalness={0.7} />
          </mesh>
        ))}
      </group>

      {/* Gantry Street Lights */}
      {[...Array(4)].map((_, idx) => {
        const zPos = -18 + idx * 14;
        const isLeft = idx % 2 === 0;
        return (
          <group key={idx} position={[isLeft ? -4.2 : 4.2, 0, zPos]}>
            <mesh position={[0, 1.8, 0]} castShadow>
              <cylinderGeometry args={[0.038, 0.05, 3.6, 8]} />
              <meshStandardMaterial color="#475569" metalness={0.8} />
            </mesh>
            <mesh position={[isLeft ? 0.65 : -0.65, 3.55, 0]} rotation={[0, 0, isLeft ? -Math.PI / 4 : Math.PI / 4] as [number, number, number]} castShadow>
              <cylinderGeometry args={[0.024, 0.03, 1.2, 8]} />
              <meshStandardMaterial color="#475569" metalness={0.8} />
            </mesh>
            <mesh position={[isLeft ? 1.05 : -1.05, 3.8, 0]} castShadow>
              <boxGeometry args={[0.22, 0.07, 0.14]} />
              <meshStandardMaterial color="#475569" metalness={0.6} />
            </mesh>
            <spotLight
              position={[isLeft ? 1.05 : -1.05, 3.75, 0]}
              angle={0.75}
              penumbra={0.3}
              intensity={2.8}
              color="#FFFDF0"
              distance={12}
            />
          </group>
        );
      })}

      {/* Parallax Skyscrapers */}
      <group ref={cityGroupRef}>
        {buildings.map((b) => (
          <group key={b.id} position={[b.x, b.h / 2, b.z]}>
            <mesh castShadow receiveShadow>
              <boxGeometry args={[b.w, b.h, b.w]} />
              <meshStandardMaterial color="#FAF9F6" roughness={0.5} />
            </mesh>
            <mesh position={[0, b.h / 2 + 0.35, 0]} castShadow>
              <cylinderGeometry args={[0.015, 0.015, 0.7, 8]} />
              <meshStandardMaterial color="#94A3B8" metalness={0.9} />
            </mesh>
            <mesh position={[0, b.h / 2 + 0.7, 0]}>
              <sphereGeometry args={[0.03, 8, 8]} />
              <meshBasicMaterial color={COLOR_AMBER} toneMapped={false} />
            </mesh>
            {[...Array(6)].map((_, rowIdx) => (
              <mesh key={rowIdx} position={[b.x > 0 ? -b.w / 2 - 0.01 : b.w / 2 + 0.01, (rowIdx - 2.5) * (b.h / 8), 0]}>
                <planeGeometry args={[0.14, 0.28]} />
                <meshBasicMaterial color={rowIdx % 3 === 0 ? COLOR_SKY_BLUE : "#FAF9F6"} />
              </mesh>
            ))}
          </group>
        ))}
      </group>

      {/* ── HIGH-FIDELITY SPORT TRANSIT VEHICLE ── */}
      <group ref={vanGroupRef} position={[-1.35, 0.65, 0]}>
        {/* Armored cargo chassis */}
        <mesh castShadow receiveShadow>
          <boxGeometry args={[1.05, 1.05, 2.4]} />
          <meshStandardMaterial color={COLOR_AMBER} roughness={0.4} />
        </mesh>
        <mesh position={[0, -0.05, 1.48]} castShadow receiveShadow>
          <boxGeometry args={[0.98, 0.88, 0.65]} />
          <meshStandardMaterial color="#F1F5F9" roughness={0.3} />
        </mesh>
        {/* Glass Windshield */}
        <mesh position={[0, 0.2, 1.815]} rotation={[-0.2, 0, 0]} receiveShadow>
          <planeGeometry args={[0.82, 0.38]} />
          <meshStandardMaterial color="#050505" roughness={0.1} metalness={0.9} transparent opacity={0.65} />
        </mesh>
        {/* Spinner Wheels */}
        <group position={[0.54, -0.42, 1.05]}>
          <mesh ref={wheelLRef} rotation={[0, 0, Math.PI / 2]} castShadow>
            <cylinderGeometry args={[0.24, 0.24, 0.22, 16]} />
            <meshStandardMaterial color="#0F172A" roughness={0.9} />
          </mesh>
        </group>
        <group position={[-0.54, -0.42, 1.05]}>
          <mesh ref={wheelRRef} rotation={[0, 0, -Math.PI / 2]} castShadow>
            <cylinderGeometry args={[0.24, 0.24, 0.22, 16]} />
            <meshStandardMaterial color="#0F172A" roughness={0.9} />
          </mesh>
        </group>
      </group>
    </group>
  );
}

// ── CUSTOM SCENE 05: DOORSTEP TRUST HANDOVER (Z = 200) ───────────────────────
function DoorstepHandoverScene() {
  const brickCoordinates = useMemo(() => {
    const list = [];
    const rows = 12;
    const cols = 8;
    for (let r = 0; r < rows; r++) {
      for (let c = 0; c < cols; c++) {
        const offset = r % 2 === 0 ? 0.12 : 0.0;
        const x = -1.8 + c * 0.52 + offset;
        const y = r * 0.24;
        list.push({ id: `${r}-${c}`, pos: [x, y, 0.01] });
      }
    }
    return list;
  }, []);

  return (
    <group position={[0, 0, 200]}>
      <group position={[0, 0, -0.6]}>
        <mesh receiveShadow>
          <boxGeometry args={[3.8, 3.2, 0.18]} />
          <meshStandardMaterial color="#E2E8F0" roughness={0.9} />
        </mesh>
        {/* Red brickwork */}
        {brickCoordinates.map((brick) => (
          <mesh key={brick.id} position={brick.pos as [number, number, number]} castShadow receiveShadow>
            <boxGeometry args={[0.48, 0.2, 0.038]} />
            <meshStandardMaterial color="#FCA5A5" roughness={0.8} />
          </mesh>
        ))}

        {/* Wood Door */}
        <group position={[0, 0.95, 0.1]}>
          <mesh castShadow receiveShadow>
            <boxGeometry args={[1.25, 2.2, 0.08]} />
            <meshStandardMaterial color="#64748B" roughness={0.9} />
          </mesh>
          <mesh position={[0, 0, 0.01]} castShadow receiveShadow>
            <boxGeometry args={[1.15, 2.1, 0.04]} />
            <meshStandardMaterial color={COLOR_WOOD} roughness={0.5} />
          </mesh>
          {[0.4, -0.4].map((y, pIdx) => (
            <mesh key={pIdx} position={[0, y, 0.032]} castShadow>
              <boxGeometry args={[0.82, 0.52, 0.015]} />
              <meshStandardMaterial color="#78350F" roughness={0.7} />
            </mesh>
          ))}
          <mesh position={[0.46, -0.15, 0.042]} castShadow>
            <cylinderGeometry args={[0.018, 0.018, 0.08, 12]} />
            <meshStandardMaterial color="#A37E2C" metalness={0.92} roughness={0.15} />
          </mesh>
        </group>
      </group>

      {/* Potted Shrub */}
      <group position={[1.25, 0.16, 0.4]}>
        <mesh castShadow>
          <cylinderGeometry args={[0.18, 0.12, 0.32, 12]} />
          <meshStandardMaterial color="#64748B" roughness={0.9} />
        </mesh>
        {[
          { p: [0, 0.22, 0] as [number, number, number], r: [0, 0, 0.2] as [number, number, number], s: [0.12, 0.42, 0.12] as [number, number, number] },
          { p: [0.08, 0.26, 0.08] as [number, number, number], r: [0.3, 0.5, -0.1] as [number, number, number], s: [0.1, 0.38, 0.1] as [number, number, number] },
          { p: [-0.08, 0.26, -0.08] as [number, number, number], r: [-0.3, -0.5, 0.1] as [number, number, number], s: [0.1, 0.38, 0.1] as [number, number, number] },
        ].map((lf, idx) => (
          <mesh key={idx} position={lf.p} rotation={lf.r} scale={lf.s} castShadow>
            <sphereGeometry args={[1, 12, 12]} />
            <meshStandardMaterial color="#166534" roughness={0.82} />
          </mesh>
        ))}
      </group>

      {/* Human courier and recipient */}
      <StylizedHighFidelityHumanoid position={[-0.58, 0.65, 0.42]} pose="handing-package" rotation={[0, Math.PI / 3.4, 0]} hasCap />
      <StylizedHighFidelityHumanoid position={[0.58, 0.65, 0.42]} pose="receiving-package" rotation={[0, -Math.PI / 3.4, 0]} hairColor="#4A3728" jacketColor="#3A322D" />

      {/* Cardboard Box */}
      <group position={[0, 0.82, 0.64]} rotation={[0.05, Math.PI / 6, -0.02]}>
        <mesh castShadow receiveShadow>
          <boxGeometry args={[0.35, 0.25, 0.35]} />
          <meshStandardMaterial color="#D97706" roughness={0.92} />
        </mesh>
        <mesh position={[0, 0, 0.005]} castShadow>
          <boxGeometry args={[0.04, 0.254, 0.36]} />
          <meshStandardMaterial color="#78350F" roughness={0.8} />
        </mesh>
        <mesh position={[0, 0.005, 0]} rotation={[0, Math.PI / 2, 0]} castShadow>
          <boxGeometry args={[0.04, 0.254, 0.36]} />
          <meshStandardMaterial color="#78350F" roughness={0.8} />
        </mesh>
      </group>

      <spotLight
        position={[0, 2.8, -0.2]}
        angle={0.85}
        penumbra={0.5}
        intensity={4.5}
        color="#FFFDF5"
        castShadow
      />
    </group>
  );
}

// ── SWIRLING SYSTEM SUNBEAM POLLEN PARTICLES ─────────────────────────────────
function SwirlingAtmosphericSparks() {
  const pointsRef = useRef<THREE.Points>(null);
  const count = 3000;

  const [positions, phases, speeds] = useMemo(() => {
    const pos = new Float32Array(count * 3);
    const phs = new Float32Array(count);
    const spds = new Float32Array(count);

    for (let i = 0; i < count; i++) {
      const z = -220 + Math.random() * 440;
      const radius = 0.5 + Math.random() * 5.5;
      const angle = Math.random() * Math.PI * 2;
      const x = Math.sin(angle) * radius;
      const y = -1.2 + Math.random() * 4.8;

      pos[i * 3] = x;
      pos[i * 3 + 1] = y;
      pos[i * 3 + 2] = z;

      phs[i] = Math.random() * Math.PI * 2;
      spds[i] = 0.15 + Math.random() * 0.35;
    }
    return [pos, phs, spds];
  }, []);

  useFrame((state) => {
    if (!pointsRef.current) return;
    const time = state.clock.getElapsedTime();
    const array = pointsRef.current.geometry.attributes.position.array as Float32Array;

    for (let i = 0; i < count; i++) {
      const phase = phases[i];
      const speed = speeds[i];
      const drift = Math.sin(time * speed + phase) * 0.0058;
      
      array[i * 3] += drift;
      array[i * 3 + 1] += Math.cos(time * speed + phase) * 0.0042;
    }
    pointsRef.current.geometry.attributes.position.needsUpdate = true;
  });

  return (
    <points ref={pointsRef}>
      <bufferGeometry>
        <bufferAttribute
          attach="attributes-position"
          args={[positions, 3]}
        />
      </bufferGeometry>
      <pointsMaterial
        color="#FFF"
        size={0.015}
        transparent
        opacity={0.45}
        sizeAttenuation
        depthWrite={false}
      />
    </points>
  );
}

// ── CUSTOM COMPONENT: INTERPOLATED LENS BREATHING CAMERA CONTROLLER ──────────
function CinematicCamera({
  stateRef,
}: {
  stateRef: React.MutableRefObject<{ scrollProgress: number }>;
}) {
  const currentLookAt = useRef(new THREE.Vector3(0, 0, 0));

  useFrame((state) => {
    const targetProgress = stateRef.current.scrollProgress;
    const { pos, look } = interpolateCamera(targetProgress);

    const ease = 0.035;
    state.camera.position.x += (pos[0] - state.camera.position.x) * ease;
    state.camera.position.y += (pos[1] - state.camera.position.y) * ease;
    state.camera.position.z += (pos[2] - state.camera.position.z) * ease;

    currentLookAt.current.x += (look[0] - currentLookAt.current.x) * ease;
    currentLookAt.current.y += (look[1] - currentLookAt.current.y) * ease;
    currentLookAt.current.z += (look[2] - currentLookAt.current.z) * ease;

    state.camera.lookAt(currentLookAt.current);

    // Physical operator camera breathing
    const time = state.clock.getElapsedTime();
    const driftX = Math.sin(time * 0.85) * 0.024;
    const driftY = Math.cos(time * 0.98) * 0.018;
    state.camera.position.x += driftX;
    state.camera.position.y += driftY;
  });

  return null;
}

// ── CUSTOM COMPONENT: FLAT OVERLAY HUD SYSTEM ───────────────────────────────
function HUDOverlay({
  containerRef,
  stateRef,
}: {
  containerRef: React.RefObject<HTMLDivElement | null>;
  stateRef: React.MutableRefObject<{ scrollProgress: number }>;
}) {
  const activeSceneStepRef = useRef<HTMLSpanElement>(null);
  const activeSceneLabelRef = useRef<HTMLSpanElement>(null);
  const operationalProgressRef = useRef<HTMLSpanElement>(null);
  const timecodeRef = useRef<HTMLSpanElement>(null);

  useEffect(() => {
    const onScroll = () => {
      const progress = stateRef.current.scrollProgress;
      const frameIndex = Math.min(9, Math.floor(progress * 10));
      
      const frameTitles = [
        "LE TERMINAL — AFFECTATION INITIALE",
        "L'AFFECTATION — BOUTON DE TRANSMISSION",
        "L'IMPULSION — OPTIMISATEUR SYSTÈME",
        "LE SIGNAL — NOTIFICATION SUR LE PORTABLE",
        "L'ACCEPTATION — CONVERSION DU DRIVER",
        "LA CLÉ — CABINE PRÊT AU DÉPART",
        "L'OMBRE — ACHEMINEMENT PAR ROUTE EST",
        "LA VILLE — TRANSIT SAHEL SIDI BOUZID",
        "LA RENCONTRE — CARD-BOX DELIVERED",
        "LA BOUCLE — OPÉRATIONS COMPLÈTES ✓"
      ];

      const progressLabeList = [
        "0% — COMMANDE REÇUE",
        "10% — VALIDATION CLAVIER",
        "22% — TRAJET COMPILÉ",
        "35% — CHAUFFEUR NOTIFIÉ",
        "50% — MISSION ACCEPTÉE",
        "62% — MOTEUR DÉMARRÉ",
        "75% — EN TRANSIT ROUTE",
        "85% — SOUSSE EN APPROCHE",
        "95% — COLIS TRANSMIS",
        "100% — TRAÇABILITÉ COMPLÈTE"
      ];

      const timecodes = [
        "00:01:23", "00:01:24", "00:01:27", "00:01:30", "00:01:34",
        "00:01:38", "00:01:42", "00:01:46", "00:01:50", "00:01:55"
      ];

      if (activeSceneStepRef.current && activeSceneLabelRef.current && operationalProgressRef.current && timecodeRef.current) {
        activeSceneStepRef.current.innerText = `FRAME 0${frameIndex + 1} / 10`;
        activeSceneLabelRef.current.innerText = frameTitles[frameIndex];
        operationalProgressRef.current.innerText = progressLabeList[frameIndex];
        timecodeRef.current.innerText = `TC: ${timecodes[frameIndex]}`;
      }
    };

    const trigger = ScrollTrigger.create({
      trigger: containerRef.current,
      start: 'top top',
      end: 'bottom bottom',
      onUpdate: onScroll,
    });

    return () => trigger.kill();
  }, [containerRef, stateRef]);

  return (
    <div
      style={{
        position: 'absolute',
        inset: 0,
        pointerEvents: 'none',
        zIndex: 50,
        display: 'flex',
        flexDirection: 'column',
        justifyContent: 'space-between',
        padding: 'clamp(28px, 4vw, 48px)',
        fontFamily: "'JetBrains Mono', ui-monospace, monospace",
        color: '#1A1614',
        letterSpacing: '0.12em',
        fontSize: 10,
        textTransform: 'uppercase',
      }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
        <span ref={activeSceneStepRef} style={{ color: 'rgba(26,22,20,0.45)' }}>
          FRAME 01 / 10
        </span>
        <span ref={activeSceneLabelRef} style={{ fontWeight: 600 }}>
          LE TERMINAL — AFFECTATION INITIALE
        </span>
      </div>

      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-end' }}>
        <span ref={operationalProgressRef} style={{ color: COLOR_AMBER, fontWeight: 600 }}>
          0% — COMMANDE REÇUE
        </span>
        <span ref={timecodeRef} style={{ color: 'rgba(26,22,20,0.45)' }}>
          TC: 00:01:23
        </span>
      </div>
    </div>
  );
}

// ── MAIN EXPORT COMPONENT ───────────────────────────────────────────────────
export function CinematicShortFilmSection() {
  const containerRef = useRef<HTMLDivElement>(null);
  const stateRef = useRef({ scrollProgress: 0 });

  useEffect(() => {
    if (!containerRef.current) return;

    const trigger = ScrollTrigger.create({
      trigger: containerRef.current,
      start: 'top top',
      end: 'bottom bottom',
      scrub: 1.0,
      onUpdate: (self) => {
        stateRef.current.scrollProgress = self.progress;
      },
    });

    return () => {
      trigger.kill();
    };
  }, []);

  return (
    <section ref={containerRef} className="relative h-[550vh] bg-[var(--hover-bg)]">
      <div className="sticky top-0 h-screen w-full overflow-hidden">
        
        {/* Real-time 3D WebGL Canvas Viewport */}
        <Canvas
          shadows
          gl={{ antialias: true, alpha: false, stencil: false, depth: true }}
          dpr={[1, 1.5]}
          camera={{ fov: 38, near: 0.1, far: 800 }}
        >
          <color attach="background" args={[COLOR_BG]} />
          
          <fogExp2 attach="fog" args={[COLOR_BG, 0.0048]} />

          {/* Natural Hemisphere Light */}
          <hemisphereLight
            args={[COLOR_SKY_BLUE, COLOR_BG, 1.4]}
            position={[0, 25, 0]}
          />

          {/* Sunny Direct Sun Light */}
          <directionalLight
            position={[8, 18, 8]}
            intensity={3.2}
            color="#FFFDF5"
            castShadow
            shadow-mapSize-width={2048}
            shadow-mapSize-height={2048}
            shadow-camera-near={0.5}
            shadow-camera-far={100}
            shadow-camera-left={-6}
            shadow-camera-right={6}
            shadow-camera-top={6}
            shadow-camera-bottom={-6}
          />

          {/* 3D SCENE STACK */}
          
          {/* Scene 1 — Z = -200 (Dispatcher Desk Console) */}
          <DispatcherWorkspaceScene />

          {/* Scene 2 — Z = -100 (Smartphone Vibration) */}
          <SmartphoneVibrationScene stateRef={stateRef} />

          {/* Scene 3 — Z = 0 (Vehicle Cockpit Prep) */}
          <VehicleCockpitPrepScene />

          {/* Scene 4 — Z = 100 (Transit Parallax Highway) */}
          <CityTransitVanScene stateRef={stateRef} />

          {/* Scene 5 — Z = 200 (Doorstep Handover) */}
          <DoorstepHandoverScene />

          {/* Swirling Sunbeam Pollen Motes */}
          <SwirlingAtmosphericSparks />

          {/* Heavy Gliding Cinematic Camera */}
          <CinematicCamera stateRef={stateRef} />
        </Canvas>

        {/* Dynamic Telemetry HUD Overlay */}
        <HUDOverlay containerRef={containerRef} stateRef={stateRef} />

        {/* ── CINEMATIC POST-PRODUCTION FILTER EFFECTS OVERLAYS ──────────────── */}

        <div
          style={{
            position: 'absolute',
            inset: 0,
            pointerEvents: 'none',
            background: 'radial-gradient(circle, transparent 48%, rgba(250,247,242,0.12) 75%, rgba(250,247,242,0.92) 100%)',
            zIndex: 35,
          }}
        />

        <div
          style={{
            position: 'absolute',
            inset: 0,
            pointerEvents: 'none',
            background: 'radial-gradient(circle at 82% 12%, rgba(252,211,77,0.16) 0%, transparent 55%)',
            mixBlendMode: 'screen',
            zIndex: 38,
          }}
        />

        {/* Full-Screen SVG Analog Film Grain Texture */}
        <svg 
          style={{
            position: 'absolute',
            inset: 0,
            width: '100%',
            height: '100%',
            pointerEvents: 'none',
            mixBlendMode: 'overlay',
            opacity: 0.024,
            zIndex: 40,
          }}
        >
          <filter id="film-grain">
            <feTurbulence type="fractalNoise" baseFrequency="0.82" numOctaves="3" stitchTiles="stitch" />
            <feColorMatrix type="matrix" values="1 0 0 0 0  0 1 0 0 0  0 0 1 0 0  0 0 0 0.12 0" />
          </filter>
          <rect width="100%" height="100%" filter="url(#film-grain)" />
        </svg>

      </div>
    </section>
  );
}
