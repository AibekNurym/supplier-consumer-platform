/**
 * WCAG 2.1 AA Contrast Ratio Checker
 * 
 * WCAG 2.1 AA requires:
 * - Normal text (less than 18pt or 14pt bold): 4.5:1
 * - Large text (18pt+ or 14pt+ bold): 3:1
 */

/**
 * Convert hex color to RGB
 */
function hexToRgb(hex) {
  const result = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(hex);
  return result ? {
    r: parseInt(result[1], 16),
    g: parseInt(result[2], 16),
    b: parseInt(result[3], 16)
  } : null;
}

/**
 * Calculate relative luminance
 * https://www.w3.org/WAI/GL/wiki/Relative_luminance
 */
function getLuminance(rgb) {
  const [r, g, b] = [rgb.r / 255, rgb.g / 255, rgb.b / 255].map(val => {
    return val <= 0.03928 ? val / 12.92 : Math.pow((val + 0.055) / 1.055, 2.4);
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/**
 * Calculate contrast ratio between two colors
 * https://www.w3.org/WAI/GL/wiki/Contrast_ratio
 */
export function getContrastRatio(color1, color2) {
  const rgb1 = typeof color1 === 'string' ? hexToRgb(color1) : color1;
  const rgb2 = typeof color2 === 'string' ? hexToRgb(color2) : color2;
  
  if (!rgb1 || !rgb2) return null;
  
  const lum1 = getLuminance(rgb1);
  const lum2 = getLuminance(rgb2);
  
  const lighter = Math.max(lum1, lum2);
  const darker = Math.min(lum1, lum2);
  
  return (lighter + 0.05) / (darker + 0.05);
}

/**
 * Check if contrast meets WCAG 2.1 AA standards
 */
export function meetsWCAGAA(foreground, background, isLargeText = false) {
  const ratio = getContrastRatio(foreground, background);
  if (!ratio) return { pass: false, ratio: null, reason: 'Invalid color format' };
  
  const requiredRatio = isLargeText ? 3.0 : 4.5;
  const pass = ratio >= requiredRatio;
  
  return {
    pass,
    ratio: Math.round(ratio * 100) / 100,
    required: requiredRatio,
    level: pass ? 'AA' : 'FAIL',
    message: pass 
      ? `✅ Passes WCAG 2.1 AA (${ratio.toFixed(2)}:1 >= ${requiredRatio}:1)`
      : `❌ Fails WCAG 2.1 AA (${ratio.toFixed(2)}:1 < ${requiredRatio}:1)`
  };
}

/**
 * Test common text/background combinations
 */
export function testCommonCombinations() {
  const commonCombos = [
    { fg: '#000000', bg: '#FFFFFF', label: 'Black on White' },
    { fg: '#FFFFFF', bg: '#000000', label: 'White on Black' },
    { fg: '#333333', bg: '#FFFFFF', label: 'Dark Gray on White' },
    { fg: '#666666', bg: '#FFFFFF', label: 'Medium Gray on White' },
    { fg: '#FFFFFF', bg: '#1E1E1E', label: 'White on Dark Gray' },
    { fg: '#FF7518', bg: '#1C1917', label: 'Halloween Orange on Dark' },
    { fg: '#2DD4BF', bg: '#FFFFFF', label: 'Aqua on White' },
    { fg: '#6BAA75', bg: '#171212', label: 'Forest Green on Dark' },
  ];
  
  const results = commonCombos.map(combo => ({
    ...combo,
    normal: meetsWCAGAA(combo.fg, combo.bg, false),
    large: meetsWCAGAA(combo.fg, combo.bg, true),
  }));
  
  return results;
}

// Export for use in browser console or tests
if (typeof window !== 'undefined') {
  window.contrastChecker = {
    getContrastRatio,
    meetsWCAGAA,
    testCommonCombinations
  };
}









