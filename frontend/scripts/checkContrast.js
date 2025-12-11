#!/usr/bin/env node

/**
 * WCAG 2.1 AA Contrast Ratio Checker
 * 
 * This script checks common color combinations used in the app
 * Run with: node scripts/checkContrast.js
 */

import { getContrastRatio, meetsWCAGAA, testCommonCombinations } from '../src/utils/contrastChecker.js';

console.log('🎨 WCAG 2.1 AA Contrast Ratio Checker\n');
console.log('='.repeat(60));

// Test common color combinations
const results = testCommonCombinations();

console.log('\n📊 Normal Text Contrast (Requires 4.5:1)\n');
results.forEach(({ label, fg, bg, normal }) => {
  const status = normal.pass ? '✅' : '❌';
  console.log(`${status} ${label}: ${normal.ratio}:1 ${normal.message}`);
});

console.log('\n📊 Large Text Contrast (Requires 3:1)\n');
results.forEach(({ label, fg, bg, large }) => {
  const status = large.pass ? '✅' : '❌';
  console.log(`${status} ${label}: ${large.ratio}:1 ${large.message}`);
});

// Test DaisyUI theme colors
console.log('\n\n🎭 Testing DaisyUI Theme Colors\n');
console.log('='.repeat(60));

const themeColors = {
  light: {
    primary: '#570df8',
    secondary: '#f000b8',
    accent: '#37cdbe',
    neutral: '#3d4451',
    'base-100': '#ffffff',
    'base-200': '#f2f2f2',
    'base-300': '#e5e6e6',
    'base-content': '#1f2937',
  },
  dark: {
    primary: '#661ae6',
    secondary: '#da1898',
    accent: '#00d9ff',
    neutral: '#2a323c',
    'base-100': '#1d232a',
    'base-200': '#191e24',
    'base-300': '#15191e',
    'base-content': '#a6adbb',
  },
};

Object.entries(themeColors).forEach(([themeName, colors]) => {
  console.log(`\n${themeName.toUpperCase()} Theme:`);
  console.log('-'.repeat(40));
  
  // Test primary text on base-100
  const primaryOnBase = meetsWCAGAA(colors['base-content'], colors['base-100'], false);
  console.log(`Primary text on base-100: ${primaryOnBase.pass ? '✅' : '❌'} ${primaryOnBase.ratio}:1`);
  
  // Test primary color text on base-100
  const primaryColorOnBase = meetsWCAGAA(colors.primary, colors['base-100'], false);
  console.log(`Primary color on base-100: ${primaryColorOnBase.pass ? '✅' : '❌'} ${primaryColorOnBase.ratio}:1`);
  
  // Test base-content on primary
  const baseOnPrimary = meetsWCAGAA(colors['base-content'], colors.primary, false);
  console.log(`Base content on primary: ${baseOnPrimary.pass ? '✅' : '❌'} ${baseOnPrimary.ratio}:1`);
});

console.log('\n\n📝 Summary');
console.log('='.repeat(60));
const allPass = results.every(r => r.normal.pass && r.large.pass);
if (allPass) {
  console.log('✅ All tested color combinations pass WCAG 2.1 AA standards!');
} else {
  console.log('⚠️  Some color combinations may need adjustment for WCAG 2.1 AA compliance.');
  console.log('\nRecommendations:');
  console.log('- Use darker text colors on light backgrounds');
  console.log('- Use lighter text colors on dark backgrounds');
  console.log('- Ensure contrast ratio of at least 4.5:1 for normal text');
  console.log('- Ensure contrast ratio of at least 3:1 for large text (18pt+)');
}

console.log('\n💡 Tip: Use the browser console to test specific colors:');
console.log('   window.contrastChecker.meetsWCAGAA("#FFFFFF", "#000000", false)');









