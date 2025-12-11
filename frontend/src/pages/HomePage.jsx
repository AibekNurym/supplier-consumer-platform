import React, { useEffect } from 'react'
import { useProductStore } from '../store/useProductStore'
import { useAuth } from '../contexts/AuthContext'
import { useNavigate } from 'react-router-dom'
import { PackageIcon, PlusCircleIcon, RefreshCwIcon } from 'lucide-react';
import ProductCard from '../components/ProductCard';
import AddProductModal from '../components/AddProductModal';
import { useTranslation } from 'react-i18next';

function HomePage() {
  const { products, loading, error, fetchProducts } = useProductStore();
  const { api, isAuthenticated, loading: authLoading, user, hasPermission } = useAuth();
  const navigate = useNavigate();
  const { t } = useTranslation();
  
  useEffect(() => {
    if (!authLoading && !isAuthenticated) {
      navigate('/login');
      return;
    }
    
    if (isAuthenticated) {
      fetchProducts(api);
    }
  }, [fetchProducts, api, isAuthenticated, authLoading, navigate]);

  // Show loading while checking authentication
  if (authLoading) {
    return (
      <div className="min-h-screen bg-base-200 flex items-center justify-center">
        <div className="loading loading-spinner loading-lg"></div>
      </div>
    );
  }

  // Don't render anything if not authenticated (will redirect)
  if (!isAuthenticated) {
    return null;
  }
  console.log("products:", products);

  return (
    <main id="main-content" className='max-w-6xl mx-auto px-4 py-8' role="main">
      <div className='flex justify-between items-center mb-8'>
        <div>
          <h1 className="text-3xl font-bold mb-2">{t('Products')}</h1>
          <p className="text-base-content/70">
            {t('Welcome back, {{name}}! Manage your product inventory.', { name: user?.firstName ?? '' })}
          </p>
        </div>
        <div className="flex gap-2">
          {hasPermission('products.create') && (
            <button className='btn btn-primary' onClick={() => document.getElementById('add_product_modal').showModal()}>
              <PlusCircleIcon className='size-5 mr-2' />
              {t('Add New Product')}
            </button>
          )}
          <button 
            className='btn btn-ghost btn-circle' 
            onClick={() => fetchProducts(api)}
            aria-label={t('Refresh products list')}
          >
            <RefreshCwIcon className='size-5' aria-hidden="true" />
          </button>
        </div>
      </div>

      {hasPermission('products.create') && <AddProductModal />}

      {error && <div className='alert alert-error mb-8'> {error} </div>}

      {products.length === 0 && !loading && (
        <div className="flex flex-col justify-center items-center h-96 space-y-4">
          <div className="bg-base-100 rounded-full p-6">
            <PackageIcon className="size-12" />
          </div>
          <div className="text-center space-y-2">
            <h3 className="text-2xl font-semibold ">{t('No products found')}</h3>
            <p className="text-gray-500 max-w-sm">
              {t('Get started by adding your first product to the inventory')}
            </p>
          </div>
        </div>
      )}

      {loading ? (
        <div className='flex justify-center items-center h-64'>
          <div className='loading loading-spinner loading-lg' />
        </div>
      ) : (
        <div className='grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6'>
          {products.map(product => (
            <ProductCard key={product.id} product={product} />
          ))}
        </div>
      )}
    </main>
  )
}

export default HomePage