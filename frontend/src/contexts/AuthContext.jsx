import { createContext, useContext, useReducer, useEffect } from 'react';
import axios from 'axios';
import toast from 'react-hot-toast';
import i18next from 'i18next';

// API Configuration
const API_BASE_URL = import.meta.env.VITE_API_URL || '/api';

// Create axios instance with default config
const api = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
});

// Auth Context
const AuthContext = createContext();

// Auth reducer
const authReducer = (state, action) => {
  switch (action.type) {
    case 'LOGIN_START':
      return {
        ...state,
        loading: true,
        error: null,
      };
    case 'LOGIN_SUCCESS':
      return {
        ...state,
        loading: false,
        isAuthenticated: true,
        user: action.payload.user,
        tokens: action.payload.tokens,
        error: null,
      };
    case 'LOGIN_FAILURE':
      return {
        ...state,
        loading: false,
        isAuthenticated: false,
        user: null,
        tokens: null,
        error: action.payload,
      };
    case 'LOGOUT':
      return {
        ...state,
        isAuthenticated: false,
        user: null,
        tokens: null,
        error: null,
      };
    case 'UPDATE_USER':
      return {
        ...state,
        user: { ...state.user, ...action.payload },
      };
    case 'SET_LOADING':
      return {
        ...state,
        loading: action.payload,
      };
    case 'SET_ERROR':
      return {
        ...state,
        error: action.payload,
      };
    case 'CLEAR_ERROR':
      return {
        ...state,
        error: null,
      };
    default:
      return state;
  }
};

// Initial state
const initialState = {
  isAuthenticated: false,
  user: null,
  tokens: null,
  loading: true,
  error: null,
};

// Auth Provider Component
export const AuthProvider = ({ children }) => {
  const [state, dispatch] = useReducer(authReducer, initialState);

  // Set up axios interceptors
  useEffect(() => {
    // Request interceptor to add auth token
    const requestInterceptor = api.interceptors.request.use(
      (config) => {
        const tokens = JSON.parse(localStorage.getItem('tokens') || 'null');
        if (tokens?.accessToken) {
          config.headers.Authorization = `Bearer ${tokens.accessToken}`;
        }
        return config;
      },
      (error) => {
        return Promise.reject(error);
      }
    );

    // Response interceptor to handle token refresh
    const responseInterceptor = api.interceptors.response.use(
      (response) => response,
      async (error) => {
        const originalRequest = error.config;

        if (error.response?.status === 401 && !originalRequest._retry) {
          originalRequest._retry = true;

          try {
            const tokens = JSON.parse(localStorage.getItem('tokens') || 'null');
            if (tokens?.refreshToken) {
              const response = await api.post('/auth/refresh-token', {
                refreshToken: tokens.refreshToken,
              });

              const newTokens = response.data.data;
              localStorage.setItem('tokens', JSON.stringify(newTokens));
              
              // Update the original request with new token
              originalRequest.headers.Authorization = `Bearer ${newTokens.accessToken}`;
              
              return api(originalRequest);
            }
          } catch (refreshError) {
            // Refresh failed, logout user
            logout();
            return Promise.reject(refreshError);
          }
        }

        return Promise.reject(error);
      }
    );

    // Cleanup interceptors
    return () => {
      api.interceptors.request.eject(requestInterceptor);
      api.interceptors.response.eject(responseInterceptor);
    };
  }, []);

  // Initialize auth state from localStorage
  useEffect(() => {
    const initializeAuth = async () => {
      try {
        const tokens = JSON.parse(localStorage.getItem('tokens') || 'null');
        const user = JSON.parse(localStorage.getItem('user') || 'null');

        if (tokens?.accessToken && user) {
          // Verify token is still valid by fetching profile
          const response = await api.get('/auth/profile');
          dispatch({
            type: 'LOGIN_SUCCESS',
            payload: {
              user: response.data.data.user,
              tokens,
            },
          });
        } else {
          dispatch({ type: 'SET_LOADING', payload: false });
        }
      } catch (error) {
        console.log('Auth initialization error:', error);
        // Token is invalid, clear storage
        localStorage.removeItem('tokens');
        localStorage.removeItem('user');
        dispatch({ type: 'SET_LOADING', payload: false });
      }
    };

    // Add a timeout to prevent infinite loading
    const timeout = setTimeout(() => {
      dispatch({ type: 'SET_LOADING', payload: false });
    }, 5000);

    initializeAuth().finally(() => {
      clearTimeout(timeout);
    });
  }, []);

  // Login function
  const login = async (email, password) => {
    try {
      dispatch({ type: 'LOGIN_START' });

      const response = await api.post('/auth/login', { email, password });
      const { user, tokens } = response.data.data;

      // Store in localStorage
      localStorage.setItem('tokens', JSON.stringify(tokens));
      localStorage.setItem('user', JSON.stringify(user));

      dispatch({
        type: 'LOGIN_SUCCESS',
        payload: { user, tokens },
      });

      toast.success(i18next.t('Welcome back, {{name}}!', { name: user.firstName || '' }));
      return { success: true };
    } catch (error) {
      const errorMessage = error.response?.data?.message || 'Login failed';
      const companyStatus = error.response?.data?.companyStatus;
      const rejectionMessage = error.response?.data?.rejectionMessage;
      
      dispatch({
        type: 'LOGIN_FAILURE',
        payload: errorMessage,
      });
      
      if (companyStatus === 'pending') {
        toast.error(i18next.t('Wait until the admin approves you.'));
      } else if (companyStatus === 'rejected') {
        toast.error(i18next.t('Your company is kinda lame! {{reason}}', { reason: rejectionMessage || '' }), { duration: 8000 });
      } else {
        toast.error(i18next.t(errorMessage, { defaultValue: errorMessage }));
      }
      
      return { success: false, error: errorMessage, companyStatus, rejectionMessage };
    }
  };

  // Register function (for internal users - kept for backward compatibility)
  const register = async (userData) => {
    try {
      dispatch({ type: 'LOGIN_START' });

      const response = await api.post('/auth/register', userData);
      const { user, tokens } = response.data.data;

      // Store in localStorage
      localStorage.setItem('tokens', JSON.stringify(tokens));
      localStorage.setItem('user', JSON.stringify(user));

      dispatch({
        type: 'LOGIN_SUCCESS',
        payload: { user, tokens },
      });

      toast.success(i18next.t('Welcome, {{name}}!', { name: user.firstName || '' }));
      return { success: true };
    } catch (error) {
      const errorMessage = error.response?.data?.message || 'Registration failed';
      dispatch({
        type: 'LOGIN_FAILURE',
        payload: errorMessage,
      });
      toast.error(i18next.t(errorMessage, { defaultValue: errorMessage }));
      return { success: false, error: errorMessage };
    }
  };

  // Register company function
  const registerCompany = async (companyData, documents) => {
    try {
      dispatch({ type: 'LOGIN_START' });

      const formData = new FormData();
      formData.append('companyName', companyData.companyName);
      formData.append('email', companyData.email);
      formData.append('password', companyData.password);
      formData.append('firstName', companyData.firstName);
      formData.append('lastName', companyData.lastName);
      if (companyData.phone) formData.append('phone', companyData.phone);
      
      // Append documents
      if (documents && documents.length > 0) {
        documents.forEach((doc) => {
          formData.append('documents', doc);
        });
      }

      const response = await api.post('/company/register', formData, {
        headers: {
          'Content-Type': 'multipart/form-data',
        },
      });

      dispatch({ type: 'LOGIN_FAILURE', payload: null });
      
      toast.success(i18next.t('Company registration submitted successfully. Please wait for admin approval.'));
      return { success: true, data: response.data };
    } catch (error) {
      const errorMessage = error.response?.data?.message || 'Company registration failed';
      dispatch({
        type: 'LOGIN_FAILURE',
        payload: errorMessage,
      });
      toast.error(i18next.t(errorMessage, { defaultValue: errorMessage }));
      return { success: false, error: errorMessage };
    }
  };

  // Logout function
  const logout = async () => {
    try {
      const tokens = JSON.parse(localStorage.getItem('tokens') || 'null');
      if (tokens?.refreshToken) {
        await api.post('/auth/logout', { refreshToken: tokens.refreshToken });
      }
    } catch (error) {
      console.error('Logout error:', error);
    } finally {
      // Clear localStorage
      localStorage.removeItem('tokens');
      localStorage.removeItem('user');
      
      dispatch({ type: 'LOGOUT' });
      toast.success(i18next.t('Logged out successfully'));
    }
  };

  // Update user profile
  const updateProfile = async (profileData) => {
    try {
      const response = await api.put('/auth/profile', profileData);
      const updatedUser = response.data.data;

      // Update localStorage
      localStorage.setItem('user', JSON.stringify(updatedUser));

      dispatch({
        type: 'UPDATE_USER',
        payload: updatedUser,
      });

      toast.success(i18next.t('Profile updated successfully'));
      return { success: true };
    } catch (error) {
      const errorMessage = error.response?.data?.message || 'Profile update failed';
      toast.error(i18next.t(errorMessage, { defaultValue: errorMessage }));
      return { success: false, error: errorMessage };
    }
  };

  // Change password
  const changePassword = async (currentPassword, newPassword) => {
    try {
      await api.put('/auth/change-password', {
        currentPassword,
        newPassword,
      });

      toast.success(i18next.t('Password changed successfully'));
      return { success: true };
    } catch (error) {
      const errorMessage = error.response?.data?.message || 'Password change failed';
      toast.error(i18next.t(errorMessage, { defaultValue: errorMessage }));
      return { success: false, error: errorMessage };
    }
  };

  // Check if user has permission
  const hasPermission = (permission) => {
    return state.user?.permissions?.includes(permission) || false;
  };

  // Check if user has role
  const hasRole = (role) => {
    return state.user?.roleName === role;
  };

  // Check if user has any of the specified roles
  const hasAnyRole = (roles) => {
    return roles.includes(state.user?.roleName);
  };

  const value = {
    ...state,
    login,
    register,
    registerCompany,
    logout,
    updateProfile,
    changePassword,
    hasPermission,
    hasRole,
    hasAnyRole,
    api, // Export api instance for other components to use
  };

  return (
    <AuthContext.Provider value={value}>
      {children}
    </AuthContext.Provider>
  );
};

// Custom hook to use auth context
export const useAuth = () => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
};

// Export the API instance for use in other components
export { api };
