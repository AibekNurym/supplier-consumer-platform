import { DollarSignIcon, ImageIcon, Package2Icon, PlusCircleIcon, ClipboardListIcon, BoxesIcon, FileTextIcon } from "lucide-react";
import { useProductStore } from "../store/useProductStore";
import { useAuth } from "../contexts/AuthContext";
import { useTranslation } from 'react-i18next';

function AddProductModal() {
  const { addProduct, formData, setFormData, loading } = useProductStore();
  const { api } = useAuth();
  const { t } = useTranslation();

  return (
    <dialog 
      id="add_product_modal" 
      className="modal"
      role="dialog"
      aria-labelledby="add-product-title"
      aria-modal="true"
    >
      <div className="modal-box">
        {/* CLOSE BUTTON */}
        <form method="dialog">
          <button 
            className="btn btn-sm btn-circle btn-ghost absolute right-2 top-2"
            aria-label={t('Close dialog')}
          >
            <span aria-hidden="true">X</span>
          </button>
        </form>

        {/* MODAL HEADER */}
        <h3 id="add-product-title" className="font-bold text-xl mb-8">{t('Add New Product')}</h3>

        <form onSubmit={(e) => addProduct(e, api)} className="space-y-6">
          <div className="grid gap-6">
            {/* PRODUCT NAME INPUT */}
            <div className="form-control">
              <label htmlFor="product-name-input" className="label">
                <span className="label-text text-base font-medium">{t('Product Name')}</span>
              </label>
              <div className="relative">
                <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none text-base-content/50">
                  <Package2Icon className="size-5" aria-hidden="true" />
                </div>
                <input
                  id="product-name-input"
                  type="text"
                  placeholder={t('Enter product name')}
                  className="input input-bordered w-full pl-10 py-3 focus:input-primary transition-colors duration-200"
                  value={formData.name}
                  onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                  required
                  aria-required="true"
                />
              </div>
            </div>

            {/* PRODUCT PRICE INPUT */}
            <div className="form-control">
              <label htmlFor="product-price-input" className="label">
                <span className="label-text text-base font-medium">{t('Price')}</span>
              </label>
              <div className="relative">
                <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none text-base-content/50">
                  <span className="text-lg font-semibold" aria-hidden="true">₸</span>
                </div>
                <input
                  id="product-price-input"
                  type="number"
                  min="0"
                  step="0.01"
                  placeholder={t('0.00')}
                  className="input input-bordered w-full pl-10 py-3 focus:input-primary transition-colors duration-200"
                  value={formData.price}
                  onChange={(e) => setFormData({ ...formData, price: e.target.value })}
                  required
                  aria-required="true"
                />
              </div>
            </div>

            {/* DISCOUNT PERCENTAGE */}
            <div className="form-control">
              <label className="label">
                <span className="label-text text-base font-medium">{t('Discount Percentage (Optional)')}</span>
              </label>
              <div className="relative">
                <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none text-base-content/50">
                  <span className="text-lg font-semibold">%</span>
                </div>
                <input
                  type="number"
                  min="0"
                  max="100"
                  step="0.01"
                  placeholder={t('0.00')}
                  className="input input-bordered w-full pl-10 py-3 focus:input-primary transition-colors duration-200"
                  value={formData.discount_percentage}
                  onChange={(e) => setFormData({ ...formData, discount_percentage: e.target.value })}
                />
              </div>
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
              <div className="relative">
                <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none text-base-content/50">
                  <span className="text-lg">📅</span>
                </div>
                <input
                  type="number"
                  min="0"
                  step="1"
                  placeholder={t('0')}
                  className="input input-bordered w-full pl-10 py-3 focus:input-primary transition-colors duration-200"
                  value={formData.lead_time_days}
                  onChange={(e) => setFormData({ ...formData, lead_time_days: e.target.value })}
                />
              </div>
              <label className="label">
                <span className="label-text-alt">{t('Number of days to prepare/deliver this product')}</span>
              </label>
            </div>

            {/* PRODUCT IMAGE */}
            <div className="form-control">
              <label className="label">
                <span className="label-text text-base font-medium">{t('Image URL')}</span>
              </label>
              <div className="relative">
                <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none text-base-content/50">
                  <ImageIcon className="size-5" />
                </div>
                <input
                  type="text"
                  placeholder="https://example.com/image.jpg"
                  className="input input-bordered w-full pl-10 py-3 focus:input-primary transition-colors duration-200"
                  value={formData.image}
                  onChange={(e) => setFormData({ ...formData, image: e.target.value })}
                />
              </div>
            </div>

            {/* MINIMUM ORDER QUANTITY */}
            <div className="form-control">
              <label className="label">
                <span className="label-text text-base font-medium">{t('Minimum Order Quantity')}</span>
              </label>
              <div className="relative">
                <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none text-base-content/50">
                  <ClipboardListIcon className="size-5" />
                </div>
                <input
                  type="number"
                  min="1"
                  placeholder={t('e.g. 5')}
                  className="input input-bordered w-full pl-10 py-3 focus:input-primary transition-colors duration-200"
                  value={formData.minimum_order_quantity}
                  onChange={(e) => setFormData({ ...formData, minimum_order_quantity: e.target.value })}
                />
              </div>
            </div>

            {/* AVAILABLE QUANTITY */}
            <div className="form-control">
              <label className="label">
                <span className="label-text text-base font-medium">{t('Available Quantity')}</span>
              </label>
              <div className="relative">
                <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none text-base-content/50">
                  <BoxesIcon className="size-5" />
                </div>
                <input
                  type="number"
                  min="0"
                  placeholder={t('e.g. 100')}
                  className="input input-bordered w-full pl-10 py-3 focus:input-primary transition-colors duration-200"
                  value={formData.available_quantity}
                  onChange={(e) => setFormData({ ...formData, available_quantity: e.target.value })}
                />
              </div>
            </div>

          </div>

          {/* MODAL ACTIONS */}
          <div className="modal-action">
            <form method="dialog">
              <button className="btn btn-ghost" aria-label={t('Cancel and close dialog')}>{t('Cancel')}</button>
            </form>
            <button
              type="submit"
              className="btn btn-primary min-w-[120px]"
              disabled={
                !formData.name ||
                !formData.price ||
                !formData.image ||
                !formData.minimum_order_quantity ||
                !formData.available_quantity ||
                loading
              }
              aria-label={t('Add new product')}
            >
              {loading ? (
                <span className="loading loading-spinner loading-sm" aria-hidden="true" />
              ) : (
                <>
                  <PlusCircleIcon className="size-5 mr-2" aria-hidden="true" />
                  {t('Add New Product')}
                </>
              )}
            </button>
          </div>
        </form>
      </div>

      {/* BACKDROP */}
      <form method="dialog" className="modal-backdrop">
        <button>{t('close')}</button>
      </form>
    </dialog>
  );
}

export default AddProductModal;
