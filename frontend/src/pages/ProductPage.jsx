import { useNavigate, useParams } from "react-router-dom";
import { useProductStore } from "../store/useProductStore";
import { useAuth } from "../contexts/AuthContext";
import { useEffect } from "react";
import { ArrowLeftIcon, SaveIcon, Trash2Icon } from "lucide-react";
import { useTranslation } from 'react-i18next';

function ProductPage() {
  const {
    currentProduct,
    formData,
    setFormData,
    loading,
    error,
    fetchProduct,
    updateProduct,
    deleteProduct,
  } = useProductStore();

  const { api, hasPermission } = useAuth();
  const navigate = useNavigate();
  const { id } = useParams();
  const { t } = useTranslation();

  useEffect(() => {
    fetchProduct(id, api);
  }, [fetchProduct, id, api]);

  const handleDelete = async () => {
    if (window.confirm(t('Are you sure you want to delete this product?'))) {
      await deleteProduct(id, api);
      navigate("/");
    }
  };

  if (loading) {
    return (
      <div className="flex justify-center items-center min-h-screen">
        <div className="loading loading-spinner loading-lg" />
      </div>
    );
  }

  if (error) {
    return (
      <div className="container mx-auto px-4 py-8">
        <div className="alert alert-error">{error}</div>
      </div>
    );
  }
  return (
    <main id="main-content" className="container mx-auto px-4 py-8 max-w-4xl" role="main">
      <button 
        onClick={() => navigate("/")} 
        className="btn btn-ghost mb-8"
        aria-label={t('Back to products list')}
      >
        <ArrowLeftIcon className="size-4 mr-2" aria-hidden="true" />
        {t('Back to Products')}
      </button>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-8">
        {/* PRODUCT IMAGE */}
        <div className="rounded-lg overflow-hidden shadow-lg bg-base-100">
          <img
            src={currentProduct?.image}
            alt={currentProduct?.name}
            className="size-full object-cover"
          />
        </div>

        {/* PRODUCT FORM */}
        <div className="card bg-base-100 shadow-lg">
          <div className="card-body">
            <h2 className="card-title text-2xl mb-6">
              {hasPermission('products.update') ? t('Edit Product') : t('Product Details')}
            </h2>

            {hasPermission('products.update') ? (
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  updateProduct(id, api);
                }}
                className="space-y-6"
              >
              {/* PRODUCT NAME */}
              <div className="form-control">
                <label htmlFor="edit-product-name" className="label">
                  <span className="label-text text-base font-medium">{t('Product Name')}</span>
                </label>
                <input
                  id="edit-product-name"
                  type="text"
                  placeholder={t('Enter product name')}
                  className="input input-bordered w-full"
                  value={formData.name}
                  onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                  required
                  aria-required="true"
                />
              </div>

              {/* PRODUCT PRICE */}
              <div className="form-control">
                <label htmlFor="edit-product-price" className="label">
                  <span className="label-text text-base font-medium">{t('Price (₸)')}</span>
                </label>
                <input
                  id="edit-product-price"
                  type="number"
                  min="0"
                  step="0.01"
                  placeholder={t('0.00')}
                  className="input input-bordered w-full"
                  value={formData.price}
                  onChange={(e) => setFormData({ ...formData, price: e.target.value })}
                  required
                  aria-required="true"
                />
              </div>

              {/* DISCOUNT PERCENTAGE */}
              <div className="form-control">
                <label className="label">
                  <span className="label-text text-base font-medium">{t('Discount Percentage (%)')}</span>
                </label>
                <input
                  type="number"
                  min="0"
                  max="100"
                  step="0.01"
                  placeholder={t('0.00')}
                  className="input input-bordered w-full"
                  value={formData.discount_percentage}
                  onChange={(e) => setFormData({ ...formData, discount_percentage: e.target.value })}
                />
                {formData.discount_percentage && parseFloat(formData.discount_percentage) > 0 && formData.price && (
                  <label className="label">
                    <span className="label-text-alt text-primary">
                      {t('Discounted Price:')} ₸{((parseFloat(formData.price) || 0) * (1 - (parseFloat(formData.discount_percentage) || 0) / 100)).toFixed(2)}
                    </span>
                  </label>
                )}
              </div>

              {/* DELIVERY LEAD TIME */}
              <div className="form-control">
                <label className="label">
                  <span className="label-text text-base font-medium">{t('Delivery Lead Time (Days)')}</span>
                </label>
                <input
                  type="number"
                  min="0"
                  step="1"
                  placeholder={t('0')}
                  className="input input-bordered w-full"
                  value={formData.lead_time_days}
                  onChange={(e) => setFormData({ ...formData, lead_time_days: e.target.value })}
                />
                <label className="label">
                  <span className="label-text-alt">{t('Number of days to prepare/deliver this product')}</span>
                </label>
              </div>

              {/* PRODUCT IMAGE URL */}
              <div className="form-control">
                <label className="label">
                  <span className="label-text text-base font-medium">{t('Image URL')}</span>
                </label>
                <input
                  type="text"
                  placeholder="https://example.com/image.jpg"
                  className="input input-bordered w-full"
                  value={formData.image}
                  onChange={(e) => setFormData({ ...formData, image: e.target.value })}
                />
              </div>

              {/* MINIMUM ORDER QUANTITY */}
              <div className="form-control">
                <label className="label">
                  <span className="label-text text-base font-medium">{t('Minimum Order Quantity')}</span>
                </label>
                <input
                  type="number"
                  min="1"
                  placeholder={t('Enter minimum order quantity')}
                  className="input input-bordered w-full"
                  value={formData.minimum_order_quantity}
                  onChange={(e) =>
                    setFormData({ ...formData, minimum_order_quantity: e.target.value })
                  }
                />
              </div>

              {/* AVAILABLE QUANTITY */}
              <div className="form-control">
                <label className="label">
                  <span className="label-text text-base font-medium">{t('Available Quantity')}</span>
                </label>
                <input
                  type="number"
                  min="0"
                  placeholder={t('Enter available quantity')}
                  className="input input-bordered w-full"
                  value={formData.available_quantity}
                  onChange={(e) =>
                    setFormData({ ...formData, available_quantity: e.target.value })
                  }
                />
              </div>

              {/* FORM ACTIONS */}
              <div className="flex justify-between mt-8">
                {hasPermission('products.delete') && (
                  <button type="button" onClick={handleDelete} className="btn btn-error">
                    <Trash2Icon className="size-4 mr-2" />
                    {t('Delete Product')}
                  </button>
                )}

                <button
                  type="submit"
                  className="btn btn-primary"
                  disabled={
                    loading ||
                    !formData.name ||
                    !formData.price ||
                    !formData.image ||
                    !formData.minimum_order_quantity ||
                    !formData.available_quantity
                  }
                >
                  {loading ? (
                    <span className="loading loading-spinner loading-sm" />
                  ) : (
                    <>
                      <SaveIcon className="size-4 mr-2" />
                      {t('Save Changes')}
                    </>
                  )}
                </button>
              </div>
            </form>
            ) : (
              /* READ-ONLY VIEW FOR SALES USERS */
              <div className="space-y-6">
                <div className="form-control">
                  <label className="label">
                    <span className="label-text text-base font-medium">{t('Product Name')}</span>
                  </label>
                  <div className="input input-bordered w-full bg-base-200">
                    {currentProduct?.name}
                  </div>
                </div>

                <div className="form-control">
                  <label className="label">
                    <span className="label-text text-base font-medium">{t('Price (₸)')}</span>
                  </label>
                  <div className="input input-bordered w-full bg-base-200">
                    {currentProduct?.discount_percentage > 0 ? (
                      <>
                        <span className="line-through text-base-content/50 mr-2">₸{currentProduct?.price}</span>
                        <span className="text-primary font-semibold">
                          ₸{((parseFloat(currentProduct?.price) || 0) * (1 - (parseFloat(currentProduct?.discount_percentage) || 0) / 100)).toFixed(2)}
                        </span>
                        <span className="ml-2 text-sm text-success">({t('{{percent}}% off', { percent: currentProduct?.discount_percentage })})</span>
                      </>
                    ) : (
                      `₸${currentProduct?.price}`
                    )}
                  </div>
                </div>

                <div className="form-control">
                  <label className="label">
                    <span className="label-text text-base font-medium">{t('Delivery Lead Time')}</span>
                  </label>
                  <div className="input input-bordered w-full bg-base-200">
                    {t('Lead Time day', { count: currentProduct?.lead_time_days || 0 })}
                  </div>
                </div>

                <div className="form-control">
                  <label className="label">
                    <span className="label-text text-base font-medium">{t('Minimum Order Quantity')}</span>
                  </label>
                  <div className="input input-bordered w-full bg-base-200">
                    {currentProduct?.minimum_order_quantity}
                  </div>
                </div>

                <div className="form-control">
                  <label className="label">
                    <span className="label-text text-base font-medium">{t('Available Quantity')}</span>
                  </label>
                  <div className="input input-bordered w-full bg-base-200">
                    {currentProduct?.available_quantity}
                  </div>
                </div>
              </div>
            )}
          </div>
        </div>
      </div>
    </main>
  );

}

export default ProductPage